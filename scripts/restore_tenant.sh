#!/usr/bin/env bash
#
# 单院恢复脚本（E22-T06 · 验收：可把单个医院恢复到指定时间点）。
#
# 依据 tech-plan §14「单院恢复」：实例级时间点恢复到新实例 → 导出该院库 → 导入新库 →
# 切换数据源注册表（plt_tenant_resource）。不用库表级时间点恢复：它不支持含生成列的表
# （热门 JSON 字段与 pt_implant 唯一约束用了生成列，tech-plan §2 已核实）。
#
# 用法（完整参数与流程见 docs/ops/tenant-restore.md）：
#   恢复：scripts/restore_tenant.sh --tenant <code> --restore-point <ISO-8601 带时区> \
#           --mode rds --source-host <PITR 临时实例> --target-host <生产实例> ... --yes
#   本地演练：--mode local-drill --source-db <恢复点快照库> --docker-service mysql ...
#   回滚：scripts/restore_tenant.sh --rollback <工作目录>/rollback.env --yes ...
#
# 安全护栏：
#   * 租户编码白名单 ^[a-z][a-z0-9]{1,31}$（与开通编排一致），杜绝标识符拼接注入；
#   * 恢复点必须 ISO-8601 带时区、不晚于当前时间、不早于备份保留窗口（30 天，NFR-AVL-004）；
#   * 未加 --yes 只输出计划，不做任何变更；
#   * rds 模式要求租户状态为 DISABLED（先在平台端停用，阻断写入并吊销会话；试用 TRIAL
#     尚无真实数据可直接恢复）；--allow-enabled 仅 local-drill 演练可用；
#   * 绝不删除任何库 / 账号：旧库原样保留供回滚，清理按 runbook §6 人工执行；
#   * 切换只改 plt_tenant_resource 的 db_name / jdbc_url（用户名、口令不动）；
#   * 本脚本不写库内审计（审计哈希链不可由外部脚本续写），恢复动作按 runbook §7 留痕，
#     平台端停用 / 启用租户自带平台审计。
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------

TENANT=""
RESTORE_POINT=""
MODE="rds"
SOURCE_DB=""
SOURCE_HOST=""
SOURCE_PORT="3306"
SOURCE_USER=""
SOURCE_PASSWORD=""
TARGET_HOST=""
TARGET_PORT="3306"
TARGET_USER=""
TARGET_PASSWORD=""
PLATFORM_DB="crdp_platform"
DOCKER_SERVICE=""
WORK_DIR=""
STOP_AFTER="verify"
YES=0
ALLOW_ENABLED=0
ROLLBACK_FILE=""

die() {
  echo "错误：$*" >&2
  exit 2
}

usage() {
  sed -n '3,32p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[[ $# -eq 0 ]] && usage
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tenant) TENANT="${2:?}"; shift 2 ;;
    --restore-point) RESTORE_POINT="${2:?}"; shift 2 ;;
    --mode) MODE="${2:?}"; shift 2 ;;
    --source-db) SOURCE_DB="${2:?}"; shift 2 ;;
    --source-host) SOURCE_HOST="${2:?}"; shift 2 ;;
    --source-port) SOURCE_PORT="${2:?}"; shift 2 ;;
    --source-user) SOURCE_USER="${2:?}"; shift 2 ;;
    --source-password) SOURCE_PASSWORD="${2:?}"; shift 2 ;;
    --target-host) TARGET_HOST="${2:?}"; shift 2 ;;
    --target-port) TARGET_PORT="${2:?}"; shift 2 ;;
    --target-user) TARGET_USER="${2:?}"; shift 2 ;;
    --target-password) TARGET_PASSWORD="${2:?}"; shift 2 ;;
    --platform-db) PLATFORM_DB="${2:?}"; shift 2 ;;
    --docker-service) DOCKER_SERVICE="${2:?}"; shift 2 ;;
    --work-dir) WORK_DIR="${2:?}"; shift 2 ;;
    --stop-after) STOP_AFTER="${2:?}"; shift 2 ;;
    --allow-enabled) ALLOW_ENABLED=1; shift ;;
    --yes) YES=1; shift ;;
    --rollback) ROLLBACK_FILE="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done

# ---------------------------------------------------------------------------
# 连接包装：口令只经 MYSQL_PWD 环境传递，不进命令行参数与日志
# ---------------------------------------------------------------------------

resolve_passwords() {
  TARGET_PASSWORD="${CRDP_RESTORE_TARGET_PASSWORD:-$TARGET_PASSWORD}"
  SOURCE_PASSWORD="${CRDP_RESTORE_SOURCE_PASSWORD:-$SOURCE_PASSWORD}"
}

mysql_target() { # stdin 为 SQL；$1 可选库名（目标 = 生产实例的迁移 / 特权账号）
  local db="${1:-}"
  if [[ -n "$DOCKER_SERVICE" ]]; then
    MYSQL_PWD="$TARGET_PASSWORD" docker compose exec -T -e MYSQL_PWD "$DOCKER_SERVICE" \
      mysql -N -B -h "$TARGET_HOST" -P "$TARGET_PORT" -u "$TARGET_USER" ${db:+"$db"}
  else
    MYSQL_PWD="$TARGET_PASSWORD" mysql -N -B -h "$TARGET_HOST" -P "$TARGET_PORT" \
      -u "$TARGET_USER" ${db:+"$db"}
  fi
}

mysql_source() { # stdin 为 SQL；$1 可选库名（rds 模式指向 PITR 临时实例）
  local db="${1:-}"
  if [[ -n "$DOCKER_SERVICE" ]]; then
    MYSQL_PWD="$SOURCE_PASSWORD" docker compose exec -T -e MYSQL_PWD "$DOCKER_SERVICE" \
      mysql -N -B -h "$SOURCE_HOST" -P "$SOURCE_PORT" -u "$SOURCE_USER" ${db:+"$db"}
  else
    MYSQL_PWD="$SOURCE_PASSWORD" mysql -N -B -h "$SOURCE_HOST" -P "$SOURCE_PORT" \
      -u "$SOURCE_USER" ${db:+"$db"}
  fi
}

# 恢复点校验：ISO-8601 带时区、不晚于现在、不早于备份保留窗口
validate_restore_point() {
  local point="$1" err
  if ! err="$(python3 - "$point" 2>&1 <<'PY'
import datetime
import sys

raw = sys.argv[1]
try:
    moment = datetime.datetime.fromisoformat(raw)
except ValueError:
    sys.exit("恢复点不是合法的 ISO-8601 时间（应为 2027-07-13T02:30:00+08:00 这种带时区写法）")
if moment.tzinfo is None:
    sys.exit("恢复点必须带时区（如 +08:00）")
now = datetime.datetime.now(datetime.timezone.utc)
if moment > now + datetime.timedelta(minutes=5):
    sys.exit("恢复点晚于当前时间，不能恢复到未来")
if moment < now - datetime.timedelta(days=35):
    sys.exit("恢复点早于自动备份保留窗口（30 天，NFR-AVL-004；另留全量周期余量）")
PY
  )"; then
    die "$err"
  fi
}

phase_banner() { # $1 序号 $2 名称 $3 说明
  printf '\n=== [%s/5] %s · %s ===\n' "$1" "$2" "$3"
}

stop_here() { # $1 阶段名
  echo "已按 --stop-after $1 停止：注册表未切换（如需切换请去掉该参数重跑）。"
  exit 0
}

# ---------------------------------------------------------------------------
# 阶段 1：preflight（参数与连通性、租户与资源行、目标库不冲突）
# ---------------------------------------------------------------------------

TENANT_ID=""
STATUS=""
OLD_DB=""
DUMP_DB=""
OLD_JDBC_URL=""
DB_USERNAME=""
NEW_DB=""
DUMP_FILE=""
DUMP_SHA256=""
COUNTS_SQL=""
SOURCE_COUNTS=""
NEW_COUNTS=""

phase_preflight() {
  phase_banner 1 preflight "参数与连通性、租户与资源行、目标库不冲突"
  local row found taken suffix

  if [[ "$MODE" != "rds" && "$MODE" != "local-drill" ]]; then
    die "--mode 只能是 rds 或 local-drill"
  fi
  case "$STOP_AFTER" in
    preflight|export|import|switch|verify) ;;
    *) die "--stop-after 只能是 preflight / export / import / switch / verify" ;;
  esac
  if [[ "$ALLOW_ENABLED" == 1 && "$MODE" == "rds" ]]; then
    die "生产恢复必须先在平台端停用租户（--allow-enabled 仅本地演练可用）"
  fi
  if [[ -n "$ROLLBACK_FILE" ]]; then
    [[ -f "$ROLLBACK_FILE" ]] || die "回滚清单不存在：$ROLLBACK_FILE"
    [[ "$YES" == 1 ]] || die "回滚同样需要 --yes 确认"
  else
    [[ -n "$TENANT" ]] || die "缺少 --tenant（租户编码）"
    [[ "$TENANT" =~ ^[a-z][a-z0-9]{1,31}$ ]] \
      || die "租户编码只允许小写字母开头的小写字母与数字（与开通编排一致）"
    [[ -n "$RESTORE_POINT" ]] || die "缺少 --restore-point（ISO-8601 带时区的恢复点）"
    validate_restore_point "$RESTORE_POINT"
    if [[ "$MODE" == "local-drill" ]]; then
      [[ -n "$SOURCE_DB" ]] || die "local-drill 需要 --source-db 指定恢复点快照库"
      [[ "$SOURCE_DB" =~ ^t_[a-z0-9_]+$ ]] || die "--source-db 库名不合法（t_ 前缀小写数字下划线）"
    else
      [[ -n "$SOURCE_HOST" && -n "$SOURCE_USER" ]] \
        || die "rds 模式需要 --source-host / --source-user 指向 PITR 临时实例（runbook §3.1）"
    fi
  fi

  resolve_passwords
  [[ -n "$TARGET_USER" && -n "$TARGET_PASSWORD" ]] \
    || die "缺少目标实例账号（--target-user，口令建议经 CRDP_RESTORE_TARGET_PASSWORD 环境变量注入）"
  if [[ -z "$TARGET_HOST" ]]; then
    if [[ -n "$DOCKER_SERVICE" ]]; then
      TARGET_HOST="127.0.0.1" # docker compose exec 场景按容器内地址连
    else
      die "缺少 --target-host（生产实例地址）"
    fi
  fi
  if [[ -n "$DOCKER_SERVICE" ]]; then
    command -v docker >/dev/null 2>&1 || die "--docker-service 需要 docker CLI"
  fi

  printf 'SELECT 1;\n' | mysql_target >/dev/null || die "目标实例连不上（$TARGET_HOST:$TARGET_PORT）"

  if [[ -n "$ROLLBACK_FILE" ]]; then
    # 回滚模式：解析清单并白名单校验（清单值会拼进 SQL）
    local key value
    while IFS='=' read -r key value; do
      case "$key" in
        TENANT_ID) TENANT_ID="$value" ;;
        OLD_DB) OLD_DB="$value" ;;
        NEW_DB) NEW_DB="$value" ;;
        OLD_JDBC_URL) OLD_JDBC_URL="$value" ;;
      esac
    done < "$ROLLBACK_FILE"
    [[ "$TENANT_ID" =~ ^[0-9]+$ ]] || die "回滚清单 TENANT_ID 不合法"
    [[ "$OLD_DB" =~ ^t_[a-z0-9_]+$ && "$NEW_DB" =~ ^t_[a-z0-9_]+$ ]] \
      || die "回滚清单库名不合法（t_ 前缀小写数字下划线）"
    [[ -n "$OLD_JDBC_URL" && "$OLD_JDBC_URL" == jdbc:mysql:* ]] || die "回滚清单 OLD_JDBC_URL 不合法"
    return 0
  fi

  row="$(printf "SELECT t.id, t.status, r.db_name, r.db_username, r.jdbc_url\nFROM plt_tenant t JOIN plt_tenant_resource r ON r.tenant_id = t.id\nWHERE t.code = '%s';\n" "$TENANT" \
    | mysql_target "$PLATFORM_DB")" || die "读取平台库租户资源失败（plt_tenant / plt_tenant_resource）"
  [[ -n "$row" ]] || die "租户 $TENANT 未登记或缺少资源行（plt_tenant_resource）"
  IFS=$'\t' read -r TENANT_ID STATUS OLD_DB DB_USERNAME OLD_JDBC_URL <<< "$row"
  case "$STATUS" in
    PROVISIONING) die "租户开通中，不能恢复（先在开通进度页处理）" ;;
    CANCELLING|CANCELLED) die "注销中 / 已注销的租户不能恢复" ;;
    DISABLED|TRIAL) ;; # DISABLED：已停写；TRIAL：试用尚无真实数据
    *)
      if [[ "$ALLOW_ENABLED" == 1 ]]; then
        echo "提示：租户当前状态 $STATUS，演练模式允许继续（生产 rds 模式要求先停用）。"
      else
        die "租户状态为 $STATUS：先在平台端停用（吊销会话并阻断写入）再恢复；试用租户（TRIAL）可直接恢复"
      fi ;;
  esac
  [[ "$OLD_DB" == "t_$TENANT" ]] || die "资源行库名（$OLD_DB）与租户编码不符，人工排查后再执行"
  [[ "$DB_USERNAME" =~ ^[A-Za-z0-9_]+$ ]] || die "资源行账号名不合法：$DB_USERNAME"
  [[ "$OLD_JDBC_URL" == *"/$OLD_DB?"* ]] \
    || die "jdbc_url 未按 /$OLD_DB? 指向当前库（$OLD_JDBC_URL），自动重写无从下手，人工排查"

  if [[ "$MODE" == "local-drill" ]]; then
    [[ "$SOURCE_DB" != "$OLD_DB" ]] || die "--source-db 不能是租户当前库（恢复源应为恢复点快照）"
    SOURCE_HOST="$TARGET_HOST"; SOURCE_PORT="$TARGET_PORT"
    SOURCE_USER="$TARGET_USER"; SOURCE_PASSWORD="$TARGET_PASSWORD"
    DUMP_DB="$SOURCE_DB"
    found="$(printf "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '%s';\n" "$SOURCE_DB" \
      | mysql_target)"
    [[ "$found" == 1 ]] || die "恢复点快照库不存在：$SOURCE_DB"
  else
    DUMP_DB="$OLD_DB"
    printf 'SELECT 1;\n' | mysql_source >/dev/null || die "PITR 临时实例连不上（$SOURCE_HOST:$SOURCE_PORT）"
  fi

  NEW_DB="t_${TENANT}_rst$(date +%Y%m%d%H%M%S)"
  suffix=0
  while :; do
    taken="$(printf "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '%s';\n" "$NEW_DB" \
      | mysql_target)"
    [[ "$taken" == 1 ]] || break
    suffix=$((suffix + 1))
    [[ $suffix -le 49 ]] || die "目标库重名 50 次，请稍候重跑"
    NEW_DB="t_${TENANT}_rst$(date +%Y%m%d%H%M%S)_$suffix"
  done

  WORK_DIR="${WORK_DIR:-$ROOT/restore-work/$TENANT/$(date +%Y%m%d%H%M%S)}"
  mkdir -p "$WORK_DIR"
  DUMP_FILE="$WORK_DIR/t_${TENANT}.sql"
  COUNTS_SQL="$WORK_DIR/counts.sql"
  SOURCE_COUNTS="$WORK_DIR/source-counts.tsv"
  NEW_COUNTS="$WORK_DIR/new-counts.tsv"

  echo "计划："
  echo "  模式     ：$MODE（rds=PITR 临时实例导出；local-drill=本地快照库演练）"
  echo "  租户     ：$TENANT（id=$TENANT_ID，状态 $STATUS）"
  echo "  恢复点   ：$RESTORE_POINT$([[ $MODE == local-drill ]] && echo '（--source-db 即该时刻的快照）')"
  echo "  恢复源   ：${SOURCE_DB:-$SOURCE_HOST:$SOURCE_PORT/$OLD_DB}"
  echo "  库切换   ：$OLD_DB → $NEW_DB（旧库保留；平台库 $PLATFORM_DB @ $TARGET_HOST:$TARGET_PORT）"
  echo "  工作目录 ：$WORK_DIR"
  echo "  后续     ：切换后需启用租户并滚动重启应用（runbook §3.5），验证清单见 §4"
  if [[ "$YES" != 1 ]]; then
    echo "未加 --yes：仅输出计划，未做任何变更。"
    exit 0
  fi
  [[ "$STOP_AFTER" == "preflight" ]] && stop_here preflight
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 2：export（从恢复源导出租户库，剥离 DEFINER，记录行数基线）
# ---------------------------------------------------------------------------

write_counts_sql() { # $1 连接函数（mysql_target / mysql_source）$2 库名 $3 输出 tsv
  local conn="$1" db="$2" out="$3" table
  : > "$COUNTS_SQL"
  printf "SELECT table_name FROM information_schema.tables WHERE table_schema = '%s' AND table_type = 'BASE TABLE' ORDER BY table_name;\n" "$db" \
    | "$conn" > "$out.tables"
  [[ -s "$out.tables" ]] || die "库 $db 里没有基表，恢复源不对"
  while IFS= read -r table; do
    [[ "$table" =~ ^[A-Za-z0-9_]+$ ]] || die "表名不合法：$table"
    printf "SELECT '%s', COUNT(*) FROM \`%s\`.\`%s\`;\n" "$table" "$db" "$table" >> "$COUNTS_SQL"
  done < "$out.tables"
  "$conn" < "$COUNTS_SQL" > "$out" || die "统计 $db 行数失败"
  rm -f "$out.tables"
}

phase_export() {
  phase_banner 2 export "从恢复源导出租户库 $DUMP_DB（--single-transaction，剥离 DEFINER）"
  local dump_flags=(--single-transaction --triggers --routines --events --hex-blob
    --set-gtid-purged=OFF --no-tablespaces --default-character-set=utf8mb4)
  if [[ -n "$DOCKER_SERVICE" ]]; then
    MYSQL_PWD="$SOURCE_PASSWORD" docker compose exec -T -e MYSQL_PWD "$DOCKER_SERVICE" \
      mysqldump "${dump_flags[@]}" -h "$SOURCE_HOST" -P "$SOURCE_PORT" -u "$SOURCE_USER" "$DUMP_DB" \
      | sed -E 's/DEFINER=`[^`]*`@`[^`]*`//g' > "$DUMP_FILE"
  else
    MYSQL_PWD="$SOURCE_PASSWORD" mysqldump "${dump_flags[@]}" \
      -h "$SOURCE_HOST" -P "$SOURCE_PORT" -u "$SOURCE_USER" "$DUMP_DB" \
      | sed -E 's/DEFINER=`[^`]*`@`[^`]*`//g' > "$DUMP_FILE"
  fi
  [[ -s "$DUMP_FILE" ]] || die "导出为空（源库 $DUMP_DB 不存在或账号无权限）"
  grep -q 'CREATE TABLE' "$DUMP_FILE" || die "导出内容异常（没有建表语句），人工检查后再继续"
  if command -v sha256sum >/dev/null 2>&1; then
    DUMP_SHA256="$(sha256sum "$DUMP_FILE" | awk '{print $1}')"
  else
    DUMP_SHA256="$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')"
  fi
  echo "导出完成：$DUMP_FILE（sha256=$DUMP_SHA256）"
  if [[ "$MODE" == "local-drill" ]]; then
    write_counts_sql mysql_target "$DUMP_DB" "$SOURCE_COUNTS"
  else
    write_counts_sql mysql_source "$DUMP_DB" "$SOURCE_COUNTS"
  fi
  echo "恢复点行数基线：$SOURCE_COUNTS（$(wc -l < "$SOURCE_COUNTS" | tr -d ' ') 张表）"
  [[ "$STOP_AFTER" == "export" ]] && stop_here export
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 3：import（建新库导入、授权租户账号、行数比对）
# ---------------------------------------------------------------------------

phase_import() {
  phase_banner 3 import "建新库 $NEW_DB 并导入、授权、行数比对"
  printf "CREATE DATABASE \`%s\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n" "$NEW_DB" \
    | mysql_target || die "建库失败：$NEW_DB"
  mysql_target "$NEW_DB" < "$DUMP_FILE" || die "导入失败（新库 $NEW_DB 未注册，旧库未动）"
  printf "GRANT ALL PRIVILEGES ON \`%s\`.* TO \`%s\`@'%%';\n" "$NEW_DB" "$DB_USERNAME" \
    | mysql_target || die "授权失败（租户账号 $DB_USERNAME 访问不了新库）"
  write_counts_sql mysql_target "$NEW_DB" "$NEW_COUNTS"
  if ! cmp -s "$SOURCE_COUNTS" "$NEW_COUNTS"; then
    echo "行数比对不一致（$SOURCE_COUNTS vs $NEW_COUNTS）：" >&2
    diff "$SOURCE_COUNTS" "$NEW_COUNTS" | head -20 >&2 || true
    die "新库与恢复点不一致；未切换注册表，旧库未动，排障后可直接重跑"
  fi
  echo "导入完成：表数与行数与恢复点一致。"
  [[ "$STOP_AFTER" == "import" ]] && stop_here import
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 4：switch（写回滚清单，切换 plt_tenant_resource 指向新库）
# ---------------------------------------------------------------------------

phase_switch() {
  phase_banner 4 switch "切换数据源注册表（plt_tenant_resource → $NEW_DB）"
  cat > "$WORK_DIR/rollback.env" <<EOF
TENANT=$TENANT
TENANT_ID=$TENANT_ID
OLD_DB=$OLD_DB
NEW_DB=$NEW_DB
OLD_JDBC_URL=$OLD_JDBC_URL
RESTORE_POINT=$RESTORE_POINT
DUMP_SHA256=$DUMP_SHA256
SWITCHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  local affected check
  affected="$(cat <<SQL | mysql_target "$PLATFORM_DB" | tail -n 1
UPDATE plt_tenant_resource
   SET db_name = '$NEW_DB',
       jdbc_url = REPLACE(jdbc_url, '/$OLD_DB?', '/$NEW_DB?')
 WHERE tenant_id = $TENANT_ID AND db_name = '$OLD_DB';
SELECT ROW_COUNT();
SQL
)"
  [[ "$affected" == 1 ]] \
    || die "切换未命中（影响行数≠1）：注册表可能已被他人改动，人工核对 plt_tenant_resource"
  check="$(printf "SELECT db_name, db_username, jdbc_url FROM plt_tenant_resource WHERE tenant_id = %s;\n" "$TENANT_ID" \
    | mysql_target "$PLATFORM_DB")"
  [[ "$check" == "$NEW_DB"$'\t'"$DB_USERNAME"$'\t'*"/$NEW_DB?"* ]] \
    || die "切换后校验失败：$check（人工核对，必要时按 rollback.env 回滚）"
  echo "注册表已切换：$OLD_DB → $NEW_DB（账号 $DB_USERNAME 与口令不变）。"
  echo "生效步骤（runbook §3.5）：平台端启用租户（此前已停用）→ 滚动重启应用，连接池按新注册表重建。"
  echo "回滚清单：$WORK_DIR/rollback.env"
  [[ "$STOP_AFTER" == "switch" ]] && stop_here switch
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 5：verify（新库可查、迁移历史在、旧库原样保留）
# ---------------------------------------------------------------------------

phase_verify() {
  phase_banner 5 verify "新库迁移历史、旧库保留核对"
  local history old_exists
  history="$(printf "SELECT COUNT(*) FROM flyway_schema_history;\n" | mysql_target "$NEW_DB")" \
    || die "新库缺 flyway_schema_history（迁移历史未随库恢复）"
  [[ "$history" -gt 0 ]] || die "新库 flyway_schema_history 为空，恢复源不是租户库"
  old_exists="$(printf "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '%s';\n" "$OLD_DB" \
    | mysql_target)"
  [[ "$old_exists" == 1 ]] || die "旧库 $OLD_DB 不见了：本脚本不删除任何库，人工排查"
  echo "恢复完成："
  echo "  租户 $TENANT 已指向 $NEW_DB（恢复点 $RESTORE_POINT，dump sha256=$DUMP_SHA256）"
  echo "  旧库 $OLD_DB 原样保留；按 runbook §4 验证并观察 ≥7 天后，再按 §6 人工清理（含 PITR 临时实例）"
  echo "  如需回滚：scripts/restore_tenant.sh --rollback $WORK_DIR/rollback.env --yes <目标实例连接参数>"
}

# ---------------------------------------------------------------------------
# 回滚：按 rollback.env 把注册表行改回旧库（幂等保护：当前必须指向 NEW_DB）
# ---------------------------------------------------------------------------

do_rollback() {
  phase_banner 4 switch "回滚：注册表改回 $OLD_DB（当前须指向 $NEW_DB）"
  local current affected
  current="$(printf "SELECT db_name FROM plt_tenant_resource WHERE tenant_id = %s;\n" "$TENANT_ID" \
    | mysql_target "$PLATFORM_DB")"
  [[ "$current" == "$NEW_DB" ]] \
    || die "注册表当前指向 $current（不是 $NEW_DB），拒绝回滚（幂等保护）；人工核对 plt_tenant_resource"
  affected="$(cat <<SQL | mysql_target "$PLATFORM_DB" | tail -n 1
UPDATE plt_tenant_resource
   SET db_name = '$OLD_DB', jdbc_url = '$OLD_JDBC_URL'
 WHERE tenant_id = $TENANT_ID AND db_name = '$NEW_DB';
SELECT ROW_COUNT();
SQL
)"
  [[ "$affected" == 1 ]] || die "回滚未命中（影响行数≠1），人工核对"
  echo "已回滚：$NEW_DB → $OLD_DB。需再次滚动重启应用（runbook §3.5）连接池才会切回。"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

phase_preflight
if [[ -n "$ROLLBACK_FILE" ]]; then
  do_rollback
  exit 0
fi
phase_export
phase_import
phase_switch
phase_verify
