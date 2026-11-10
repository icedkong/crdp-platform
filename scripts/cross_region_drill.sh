#!/usr/bin/env bash
#
# 跨区域恢复演练脚本（E22-T07 · 验收：演练报告存档，区域内 RPO ≤ 5 分钟）。
#
# 依据 tech-plan §14「RDS 跨区域备份（北京四 → 上海一）+ IaC 重建」：整个区域不可用时，RTO 4 小时
# 目标内在备用区域（上海一 cn-east-3）重建并恢复；跨区域备份为异步、官方不保证及时性和 SLA，区域级
# RPO 不保证、以演练实测记录；区域内 RPO ≤ 5 分钟（Binlog 每 5 分钟上传，NFR-AVL-002）不变。
# 频次：每半年一次（NFR-AVL-004，与 E22-T08 归档演练同窗口），实测 RPO / RTO 记入报告并存档。
#
# 用法（完整参数与流程见 docs/ops/cross-region-drill.md）：
#   生产演练（持云账号运维执行，IaC 重建与 PITR 由控制台 / terraform 完成，本脚本负责
#   盘点、切换、验证、实测与报告）：
#     scripts/cross_region_drill.sh --mode rds --drill-id <id> \
#       --last-restorable-in-region <ISO> --last-restorable-cross-region <ISO> \
#       --standby-host <上海一重建实例地址> --standby-user <迁移账号> \
#       --region-a-jdbc-host <北京四实例地址:端口> --standby-jdbc-host <上海一实例地址:端口> \
#       [--declared-at <ISO> --rebuild-done-at <ISO> ...] --yes
#   本地演练（CI）：--mode local-drill --backup-dir <跨区域副本化身目录> \
#       --restored-platform-db <化身平台库> --objects-standby-dir <备用区域对象目录> \
#       --proof-file <验证清单> --report-archive-dir <存档目录> --docker-service mysql ... --yes
#
# 七阶段（phase_* 函数，顺序执行；--stop-after 可停在任一阶段）：
#   plan（宣告灾难，记 T0，RTO 计时开始）→ inventory（盘点两口径最近可恢复点，算实测 RPO，
#   区域内 RPO ≤ 300 秒验收门不达标即中止）→ rebuild（备用区域重建化身库 / 生产核对重建实例）→
#   restore（从跨区域副本导入平台库与租户库、对象副本就位）→ switch（注册表 jdbc_url 全部改写到
#   备用区域实例）→ verify（验证清单逐项核对、对象对齐、审计迁移历史）→ report（实测 RPO / RTO
#   报告落盘 + 存档，任一判定 FAIL 以非零退出）。
#
# 安全护栏：
#   * 区域白名单 cn-north-4 / cn-east-3（与 iac/rds-prod/params-baseline.json 一致），且故障区域
#     与备用区域不得相同；
#   * 时间戳一律 ISO-8601 带时区校验；最近可恢复点不得晚于宣告时刻；
#   * 区域内 RPO = 宣告时刻 − 区域内最近可恢复点 > 300 秒（NFR-AVL-002）→ 直接判失败中止，
#     不做任何变更；
#   * 未加 --yes 只输出计划，不做任何变更；
#   * 本脚本绝不删除任何库 / 账号，也绝不连接「故障区域」实例（区域已不可用，化身库清理由
#     校验脚本负责）；
#   * 口令只经 CRDP_DRILL_STANDBY_PASSWORD 环境变量注入，不进命令行参数与日志；
#   * 生产演练不做库内审计写入（审计哈希链不可由外部脚本续写），动作按 runbook §5 留痕。
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------

DRILL_ID=""
MODE="rds"
REGION_FAILED="cn-north-4"
REGION_STANDBY="cn-east-3"
TENANT_CODE=""
LAST_RESTORABLE_IN=""
LAST_RESTORABLE_X=""
DECLARED_AT=""
REBUILD_DONE_AT=""
RESTORE_DONE_AT=""
SWITCH_DONE_AT=""
STANDBY_HOST=""
STANDBY_PORT="3306"
STANDBY_JDBC_HOST=""
STANDBY_USER=""
STANDBY_PASSWORD=""
REGION_A_JDBC_HOST=""
BACKUP_DIR=""
RESTORED_PLATFORM_DB="crdp_platform"
OBJECTS_STANDBY_DIR=""
OBJECTS_MANIFEST=""
PROOF_FILE=""
HEALTH_URL=""
DOCKER_SERVICE=""
WORK_DIR=""
STOP_AFTER="report"
REPORT_ARCHIVE_DIR=""
YES=0

die() {
  echo "错误：$*" >&2
  exit 2
}

usage() {
  sed -n '3,39p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[[ $# -eq 0 ]] && usage
while [[ $# -gt 0 ]]; do
  case "$1" in
    --drill-id) DRILL_ID="${2:?}"; shift 2 ;;
    --mode) MODE="${2:?}"; shift 2 ;;
    --region-failed) REGION_FAILED="${2:?}"; shift 2 ;;
    --region-standby) REGION_STANDBY="${2:?}"; shift 2 ;;
    --tenant-code) TENANT_CODE="${2:?}"; shift 2 ;;
    --last-restorable-in-region) LAST_RESTORABLE_IN="${2:?}"; shift 2 ;;
    --last-restorable-cross-region) LAST_RESTORABLE_X="${2:?}"; shift 2 ;;
    --declared-at) DECLARED_AT="${2:?}"; shift 2 ;;
    --rebuild-done-at) REBUILD_DONE_AT="${2:?}"; shift 2 ;;
    --restore-done-at) RESTORE_DONE_AT="${2:?}"; shift 2 ;;
    --switch-done-at) SWITCH_DONE_AT="${2:?}"; shift 2 ;;
    --standby-host) STANDBY_HOST="${2:?}"; shift 2 ;;
    --standby-port) STANDBY_PORT="${2:?}"; shift 2 ;;
    --standby-jdbc-host) STANDBY_JDBC_HOST="${2:?}"; shift 2 ;;
    --standby-user) STANDBY_USER="${2:?}"; shift 2 ;;
    --region-a-jdbc-host) REGION_A_JDBC_HOST="${2:?}"; shift 2 ;;
    --backup-dir) BACKUP_DIR="${2:?}"; shift 2 ;;
    --restored-platform-db) RESTORED_PLATFORM_DB="${2:?}"; shift 2 ;;
    --objects-standby-dir) OBJECTS_STANDBY_DIR="${2:?}"; shift 2 ;;
    --objects-manifest) OBJECTS_MANIFEST="${2:?}"; shift 2 ;;
    --proof-file) PROOF_FILE="${2:?}"; shift 2 ;;
    --report-archive-dir) REPORT_ARCHIVE_DIR="${2:?}"; shift 2 ;;
    --health-url) HEALTH_URL="${2:?}"; shift 2 ;;
    --docker-service) DOCKER_SERVICE="${2:?}"; shift 2 ;;
    --work-dir) WORK_DIR="${2:?}"; shift 2 ;;
    --stop-after) STOP_AFTER="${2:?}"; shift 2 ;;
    --yes) YES=1; shift ;;
    -h|--help) usage ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done

# ---------------------------------------------------------------------------
# 连接包装：口令只经 MYSQL_PWD 环境传递，不进命令行参数与日志
# ---------------------------------------------------------------------------

resolve_passwords() {
  STANDBY_PASSWORD="${CRDP_DRILL_STANDBY_PASSWORD:-$STANDBY_PASSWORD}"
}

mysql_standby() { # stdin 为 SQL；$1 可选库名（备用区域重建实例的迁移 / 特权账号）
  local db="${1:-}"
  if [[ -n "$DOCKER_SERVICE" ]]; then
    MYSQL_PWD="$STANDBY_PASSWORD" docker compose exec -T -e MYSQL_PWD "$DOCKER_SERVICE" \
      mysql -N -B -h "$STANDBY_HOST" -P "$STANDBY_PORT" -u "$STANDBY_USER" ${db:+"$db"}
  else
    MYSQL_PWD="$STANDBY_PASSWORD" mysql -N -B -h "$STANDBY_HOST" -P "$STANDBY_PORT" \
      -u "$STANDBY_USER" ${db:+"$db"}
  fi
}

now_iso() {
  python3 -c 'import datetime
print(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).replace(microsecond=0).isoformat())'
}

iso_epoch() { # $1 ISO-8601 带时区 → epoch 秒
  python3 -c 'import datetime, sys
print(int(datetime.datetime.fromisoformat(sys.argv[1]).timestamp()))' "$1"
}

validate_iso() { # $1 时间戳 $2 字段名（错误信息用）
  local point="$1" err
  if ! err="$(python3 - "$point" "$2" 2>&1 <<'PY'
import datetime
import sys

raw, field = sys.argv[1], sys.argv[2]
try:
    moment = datetime.datetime.fromisoformat(raw)
except ValueError:
    sys.exit(f"{field} 不是合法的 ISO-8601 时间（应为 2027-07-13T02:30:00+08:00 这种带时区写法）")
if moment.tzinfo is None:
    sys.exit(f"{field} 必须带时区（如 +08:00）")
PY
  )"; then
    die "$err"
  fi
}

phase_banner() { # $1 序号 $2 名称 $3 说明
  printf '\n=== [%s/7] %s · %s ===\n' "$1" "$2" "$3"
}

stop_here() { # $1 阶段名
  echo "已按 --stop-after $1 停止（报告未生成；继续请去掉该参数重跑）。"
  exit 0
}

# ---------------------------------------------------------------------------
# 阶段 1：plan（宣告灾难：T0 = RTO 计时起点；参数与连通性核对）
# ---------------------------------------------------------------------------

DECLARED_TS=""
INVENTORY_DONE=""
REBUILD_DONE=""
RESTORE_DONE=""
SWITCH_DONE=""
VERIFIED_AT=""
PROOF_RESULTS=""
PROOFS_FAILED=0
OBJECTS_MISSING=""
REGISTRY_REWRITTEN=0
PROBLEMS_COUNT=0

phase_plan() {
  phase_banner 1 plan "宣告区域灾难（T0 = RTO 计时起点），核对参数与备用区域连通性"
  if [[ "$MODE" != "rds" && "$MODE" != "local-drill" ]]; then
    die "--mode 只能是 rds 或 local-drill"
  fi
  case "$STOP_AFTER" in
    plan|inventory|rebuild|restore|switch|verify|report) ;;
    *) die "--stop-after 只能是 plan / inventory / rebuild / restore / switch / verify / report" ;;
  esac
  [[ -n "$DRILL_ID" ]] || die "缺少 --drill-id（演练编号，进报告与存档文件名）"
  [[ "$DRILL_ID" =~ ^[a-z0-9][a-z0-9-]{2,63}$ ]] \
    || die "--drill-id 只允许小写字母数字与连字符（3–64 位）"
  case "$REGION_FAILED" in
    cn-north-4|cn-east-3) ;;
    *) die "--region-failed 只能是 cn-north-4 / cn-east-3（区域白名单，与 iac/rds-prod 基线一致）" ;;
  esac
  case "$REGION_STANDBY" in
    cn-north-4|cn-east-3) ;;
    *) die "--region-standby 只能是 cn-north-4 / cn-east-3（区域白名单，与 iac/rds-prod 基线一致）" ;;
  esac
  [[ "$REGION_FAILED" != "$REGION_STANDBY" ]] \
    || die "故障区域与备用区域不能相同（$REGION_FAILED）"
  [[ -n "$TENANT_CODE" ]] || die "缺少 --tenant-code（参与演练的租户编码，至少一个院做数据核对）"
  [[ "$TENANT_CODE" =~ ^[a-z][a-z0-9]{1,31}$ ]] \
    || die "租户编码只允许小写字母开头的小写字母与数字（与开通编排一致）"
  [[ -n "$LAST_RESTORABLE_IN" ]] || die "缺少 --last-restorable-in-region（区域内最近可恢复时间点，控制台实测）"
  [[ -n "$LAST_RESTORABLE_X" ]] || die "缺少 --last-restorable-cross-region（跨区域副本最近可恢复时间点，控制台实测）"
  validate_iso "$LAST_RESTORABLE_IN" "--last-restorable-in-region"
  validate_iso "$LAST_RESTORABLE_X" "--last-restorable-cross-region"
  for stamp in "$DECLARED_AT" "$REBUILD_DONE_AT" "$RESTORE_DONE_AT" "$SWITCH_DONE_AT"; do
    [[ -z "$stamp" ]] || validate_iso "$stamp" "里程碑时间戳"
  done
  [[ -n "$REGION_A_JDBC_HOST" ]] || die "缺少 --region-a-jdbc-host（故障区域实例的 jdbc 主机:端口，注册表改写源）"
  [[ "$REGION_A_JDBC_HOST" =~ ^[A-Za-z0-9.-]+:[0-9]+$ ]] || die "--region-a-jdbc-host 应为 主机:端口"
  [[ -n "$STANDBY_USER" ]] || die "缺少 --standby-user（备用区域实例账号）"
  if [[ -z "$STANDBY_JDBC_HOST" ]]; then
    [[ -n "$STANDBY_HOST" ]] || die "缺少 --standby-host（备用区域实例地址）"
    STANDBY_JDBC_HOST="${STANDBY_HOST}:${STANDBY_PORT}"
  fi
  [[ "$STANDBY_JDBC_HOST" =~ ^[A-Za-z0-9.-]+:[0-9]+$ ]] || die "--standby-jdbc-host 应为 主机:端口"
  [[ "$STANDBY_JDBC_HOST" != "$REGION_A_JDBC_HOST" ]] \
    || die "备用区域地址与故障区域地址相同（$STANDBY_JDBC_HOST），改写注册表将无意义"

  resolve_passwords
  [[ -n "$STANDBY_PASSWORD" ]] \
    || die "缺少备用区域实例口令（建议经 CRDP_DRILL_STANDBY_PASSWORD 环境变量注入）"
  if [[ -z "$STANDBY_HOST" ]]; then
    if [[ -n "$DOCKER_SERVICE" ]]; then
      STANDBY_HOST="127.0.0.1" # docker compose exec 场景按容器内地址连
    else
      die "缺少 --standby-host（备用区域重建实例地址）"
    fi
  fi
  if [[ -n "$DOCKER_SERVICE" ]]; then
    command -v docker >/dev/null 2>&1 || die "--docker-service 需要 docker CLI"
  fi

  if [[ "$MODE" == "local-drill" ]]; then
    [[ -d "$BACKUP_DIR" ]] || die "--backup-dir 不存在（跨区域备份副本化身目录）"
    [[ -s "$BACKUP_DIR/platform.sql" ]] || die "副本目录缺 platform.sql（平台库转储）"
    [[ -s "$BACKUP_DIR/tenant_${TENANT_CODE}.sql" ]] \
      || die "副本目录缺 tenant_${TENANT_CODE}.sql（租户库转储）"
    OBJECTS_MANIFEST="${OBJECTS_MANIFEST:-$BACKUP_DIR/objects-manifest.txt}"
    [[ -s "$OBJECTS_MANIFEST" ]] || die "缺少对象对齐清单（$OBJECTS_MANIFEST）"
    [[ -n "$OBJECTS_STANDBY_DIR" ]] || die "local-drill 需要 --objects-standby-dir（备用区域对象化身目录）"
    [[ "$RESTORED_PLATFORM_DB" =~ ^[a-z0-9_]+$ ]] || die "--restored-platform-db 库名不合法（小写数字下划线）"
  else
    [[ "$RESTORED_PLATFORM_DB" =~ ^[a-z0-9_]+$ ]] || die "--restored-platform-db 库名不合法（小写数字下划线）"
  fi
  if [[ -n "$PROOF_FILE" ]]; then
    [[ -s "$PROOF_FILE" ]] || die "--proof-file 不存在或为空（验证清单：表<TAB>条件<TAB>期望行数）"
  fi

  # 宣告时刻（T0）：生产可用 --declared-at 回填实际宣告时间；默认取本阶段开始时刻
  DECLARED_TS="${DECLARED_AT:-$(now_iso)}"

  printf 'SELECT 1;\n' | mysql_standby >/dev/null || die "备用区域实例连不上（$STANDBY_HOST:$STANDBY_PORT）"

  WORK_DIR="${WORK_DIR:-$ROOT/drill-work/$DRILL_ID/$(date +%Y%m%d%H%M%S)}"
  mkdir -p "$WORK_DIR"

  echo "演练计划："
  echo "  编号     ：$DRILL_ID（模式 $MODE）"
  echo "  场景     ：$REGION_FAILED 区域不可用 → $REGION_STANDBY 重建恢复（IaC 见 iac/rds-prod、iac/cce-prod）"
  echo "  宣告时刻 ：$DECLARED_TS（T0，RTO 计时起点）"
  echo "  恢复点   ：区域内 $LAST_RESTORABLE_IN / 跨区域 $LAST_RESTORABLE_X（控制台最近可恢复时间点）"
  echo "  数据核对 ：租户 $TENANT_CODE${PROOF_FILE:+，验证清单 $PROOF_FILE}"
  echo "  注册表   ：plt_tenant_resource 的 jdbc_url 主机 $REGION_A_JDBC_HOST → $STANDBY_JDBC_HOST（平台库 $RESTORED_PLATFORM_DB）"
  echo "  工作目录 ：$WORK_DIR"
  if [[ "$YES" != 1 ]]; then
    echo "未加 --yes：仅输出计划，未做任何变更。"
    exit 0
  fi
  [[ "$STOP_AFTER" == "plan" ]] && stop_here plan
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 2：inventory（盘点两口径最近可恢复点，算实测 RPO，区域内验收门）
# ---------------------------------------------------------------------------

RPO_IN_SECONDS=-1
RPO_X_SECONDS=-1

phase_inventory() {
  phase_banner 2 inventory "实测 RPO：区域内 ≤ 300 秒验收门（NFR-AVL-002），跨区域仅记录无 SLA"
  local declared_epoch in_epoch x_epoch
  declared_epoch="$(iso_epoch "$DECLARED_TS")"
  in_epoch="$(iso_epoch "$LAST_RESTORABLE_IN")"
  x_epoch="$(iso_epoch "$LAST_RESTORABLE_X")"
  RPO_IN_SECONDS=$(( declared_epoch - in_epoch ))
  RPO_X_SECONDS=$(( declared_epoch - x_epoch ))
  if (( RPO_IN_SECONDS < -2 )); then
    die "区域内最近可恢复点晚于宣告时刻（RPO 为负 $RPO_IN_SECONDS 秒），时间戳有误"
  fi
  if (( RPO_X_SECONDS < -2 )); then
    die "跨区域最近可恢复点晚于宣告时刻（RPO 为负 $RPO_X_SECONDS 秒），时间戳有误"
  fi
  echo "实测 RPO：区域内 ${RPO_IN_SECONDS} 秒（目标 ≤ 300，Binlog 每 5 分钟上传）；跨区域 ${RPO_X_SECONDS} 秒（异步复制，无 SLA，仅记录）"
  if (( RPO_IN_SECONDS > 300 )); then
    die "区域内 RPO ${RPO_IN_SECONDS} 秒 > 300 秒（NFR-AVL-002）：演练判定失败，未做任何变更；按 runbook §3 排查 Binlog 上传与备份链路后重新演练"
  fi
  INVENTORY_DONE="$(now_iso)"
  [[ "$STOP_AFTER" == "inventory" ]] && stop_here inventory
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 3：rebuild（备用区域重建：生产核对重建实例，local 建化身库）
# ---------------------------------------------------------------------------

phase_rebuild() {
  phase_banner 3 rebuild "备用区域 $REGION_STANDBY 重建（生产：iac/rds-prod + iac/cce-prod，region=$REGION_STANDBY）"
  if [[ "$MODE" == "local-drill" ]]; then
    # 化身：在同一 MySQL 实例上以独立库名模拟「上海一重建实例」的两个库
    printf "CREATE DATABASE IF NOT EXISTS \`%s\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n" "$RESTORED_PLATFORM_DB" \
      | mysql_standby || die "建化身平台库失败：$RESTORED_PLATFORM_DB"
    printf "CREATE DATABASE IF NOT EXISTS \`t_%s\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n" "$TENANT_CODE" \
      | mysql_standby || die "建化身租户库失败：t_$TENANT_CODE"
  else
    echo "生产步骤（已完成才继续，详见 runbook §3.3）："
    echo "  1) terraform -chdir=iac/rds-prod plan -var 'region=$REGION_STANDBY' ...（上海一重建实例，参数基线不变）"
    echo "  2) 上海一控制台从跨区域备份「恢复到指定时间点」到该新实例（恢复点 = $LAST_RESTORABLE_X）"
    echo "  3) terraform -chdir=iac/cce-prod plan -var 'region=$REGION_STANDBY' ...（集群 / ELB / WAF / HSS 重建）"
    local found
    found="$(printf "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '%s';\n" "$RESTORED_PLATFORM_DB" \
      | mysql_standby)"
    [[ "$found" == 1 ]] \
      || die "重建实例上没有平台库 $RESTORED_PLATFORM_DB（PITR 未完成或连错实例）"
    found="$(printf "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 't_%s';\n" "$TENANT_CODE" \
      | mysql_standby)"
    [[ "$found" == 1 ]] || die "重建实例上没有租户库 t_$TENANT_CODE（PITR 未完成或连错实例）"
  fi
  REBUILD_DONE="${REBUILD_DONE_AT:-$(now_iso)}"
  echo "重建完成：$REBUILD_DONE"
  [[ "$STOP_AFTER" == "rebuild" ]] && stop_here rebuild
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 4：restore（从跨区域副本导入平台库与租户库，对象副本就位）
# ---------------------------------------------------------------------------

restore_dump() { # $1 转储文件 $2 目标库
  local proc_ok=1
  if [[ -n "$DOCKER_SERVICE" ]]; then
    docker compose exec -T "$DOCKER_SERVICE" sh -c \
      "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" '$2'" < "$1" || proc_ok=0
  else
    mysql_standby "$2" < "$1" || proc_ok=0
  fi
  [[ "$proc_ok" == 1 ]] || die "导入 $1 → $2 失败（化身库未注册应用，可直接重跑）"
}

phase_restore() {
  phase_banner 4 restore "从跨区域副本恢复平台库与租户库（恢复点 $LAST_RESTORABLE_X），对象副本就位"
  if [[ "$MODE" == "local-drill" ]]; then
    restore_dump "$BACKUP_DIR/platform.sql" "$RESTORED_PLATFORM_DB"
    restore_dump "$BACKUP_DIR/tenant_${TENANT_CODE}.sql" "t_${TENANT_CODE}"
    # 对象对齐：备用区域对象化身目录就位（生产 = OBS 跨区域复制的备用区域桶，已异步就位）
    if [[ -d "$BACKUP_DIR/objects" ]]; then
      mkdir -p "$OBJECTS_STANDBY_DIR"
      cp -R "$BACKUP_DIR/objects/." "$OBJECTS_STANDBY_DIR/" || die "对象副本就位失败"
    fi
    # 导入完整性：重建租户库逐表行数 = 副本基线（counts 文件由备份侧生成）
    local counts="$BACKUP_DIR/counts_${TENANT_CODE}.tsv" table expect got bad=0
    if [[ -s "$counts" ]]; then
      while IFS=$'\t' read -r table expect; do
        [[ "$table" =~ ^[A-Za-z0-9_]+$ ]] || die "基线表名不合法：$table"
        got="$(printf "SELECT COUNT(*) FROM \`%s\`;\n" "$table" | mysql_standby "t_${TENANT_CODE}")"
        [[ "$got" == "$expect" ]] || { echo "行数不一致：$table 副本 $expect vs 重建 $got" >&2; bad=1; }
      done < "$counts"
      (( bad == 0 )) || die "重建租户库与副本基线行数不一致（导入不完整）"
    fi
  else
    echo "生产：数据已由 PITR 整实例恢复（rebuild 阶段核对了库在），本阶段无导入动作。"
  fi
  RESTORE_DONE="${RESTORE_DONE_AT:-$(now_iso)}"
  echo "恢复完成：$RESTORE_DONE"
  [[ "$STOP_AFTER" == "restore" ]] && stop_here restore
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 5：switch（注册表 jdbc_url 全部改写到备用区域实例地址）
# ---------------------------------------------------------------------------

phase_switch() {
  phase_banner 5 switch "注册表切换：plt_tenant_resource 的 jdbc_url 主机 → $STANDBY_JDBC_HOST"
  local affected remaining
  affected="$(cat <<SQL | mysql_standby "$RESTORED_PLATFORM_DB" | tail -n 1
UPDATE plt_tenant_resource
   SET jdbc_url = REPLACE(jdbc_url, '//$REGION_A_JDBC_HOST/', '//$STANDBY_JDBC_HOST/')
 WHERE jdbc_url LIKE '%//$REGION_A_JDBC_HOST/%';
SELECT ROW_COUNT();
SQL
)"
  REGISTRY_REWRITTEN="$affected"
  remaining="$(printf "SELECT COUNT(*) FROM plt_tenant_resource WHERE jdbc_url LIKE '%%%s%%';\n" "//$REGION_A_JDBC_HOST/" \
    | mysql_standby "$RESTORED_PLATFORM_DB")"
  [[ "$remaining" == 0 ]] \
    || die "仍有 $remaining 行 jdbc_url 指向故障区域地址（$REGION_A_JDBC_HOST），人工核对注册表"
  if [[ "$affected" -lt 1 ]]; then
    die "注册表改写未命中任何行：恢复出的 plt_tenant_resource 应指向 --region-a-jdbc-host（$REGION_A_JDBC_HOST），人工核对恢复源"
  fi
  echo "已改写 $affected 行（命中行全部改写到备用区域实例；清单外的行人工抽查）。"
  if [[ "$MODE" == "rds" ]]; then
    echo "生产后续（runbook §3.6）：CRDP_PLATFORM_JDBC_URL 指向 $STANDBY_JDBC_HOST → kubectl rollout restart"
    echo "滚动重启应用 → DNS / WAF CNAME 切到备用区域入口 → 全部租户恢复访问。"
  fi
  SWITCH_DONE="${SWITCH_DONE_AT:-$(now_iso)}"
  echo "切换完成：$SWITCH_DONE"
  [[ "$STOP_AFTER" == "switch" ]] && stop_here switch
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 6：verify（验证清单逐项核对、迁移历史、对象对齐、健康检查）
# ---------------------------------------------------------------------------

phase_verify() {
  phase_banner 6 verify "验证清单核对（恢复点前三态、迁移历史、注册表、对象对齐${HEALTH_URL:+、健康检查}）"
  local history line table where expect got key
  history="$(printf "SELECT COUNT(*) FROM flyway_schema_history;\n" | mysql_standby "t_${TENANT_CODE}")" \
    || die "重建租户库缺 flyway_schema_history（迁移历史未随库恢复）"
  [[ "$history" -gt 0 ]] || die "重建租户库 flyway_schema_history 为空，恢复源不对"
  echo "迁移历史随库恢复（flyway_schema_history ${history} 行）。"

  PROOF_RESULTS=""
  PROOFS_FAILED=0
  if [[ -n "$PROOF_FILE" ]]; then
    local where_re='^[A-Za-z0-9_ =<>()!-]*$'
    while IFS=$'\t' read -r table where expect; do
      [[ -n "${table:-}" ]] || continue
      [[ "$table" =~ ^[A-Za-z0-9_]+$ ]] || die "验证清单表名不合法：$table"
      [[ "$where" =~ $where_re ]] || die "验证清单条件含未允许字符：$where"
      [[ "$expect" =~ ^[0-9]+$ ]] || die "验证清单期望行数不是非负整数：$expect"
      got="$(printf "SELECT COUNT(*) FROM \`%s\`%s;\n" "$table" "${where:+ WHERE $where}" \
        | mysql_standby "t_${TENANT_CODE}")"
      if [[ "$got" == "$expect" ]]; then
        PROOF_RESULTS+="PASS|$table|$where|期望 $expect 实测 $got"$'\n'
      else
        PROOF_RESULTS+="FAIL|$table|$where|期望 $expect 实测 $got"$'\n'
        PROOFS_FAILED=$(( PROOFS_FAILED + 1 ))
      fi
    done < "$PROOF_FILE"
    echo "验证清单：$(printf '%s' "$PROOF_RESULTS" | grep -cE '^(PASS|FAIL)') 项，失败 $PROOFS_FAILED 项"
  fi

  OBJECTS_MISSING=""
  if [[ "$MODE" == "local-drill" ]]; then
    local key_re='^[A-Za-z0-9._/-]+$'
    while IFS= read -r key; do
      [[ -n "$key" ]] || continue
      [[ "$key" =~ $key_re ]] || die "对象清单键不合法：$key"
      [[ -e "$OBJECTS_STANDBY_DIR/$key" ]] || OBJECTS_MISSING+="$key"$'\n'
    done < "$OBJECTS_MANIFEST"
    if [[ -n "$OBJECTS_MISSING" ]]; then
      echo "对象对齐失败：恢复点之前的对象在备用区域副本中缺失：" >&2
      printf '%s' "$OBJECTS_MISSING" | sed 's/^/  - /' >&2
    else
      echo "对象对齐：清单内对象全部在备用区域副本中（恢复点之前的对象可用）。"
    fi
  fi

  if [[ -n "$HEALTH_URL" ]]; then
    curl -sf "$HEALTH_URL" | grep -q '"UP"' \
      || echo "警告：$HEALTH_URL 未返回 UP（滚动重启未完成则预期内，报告按 verify 通过时刻记 RTO）"
  fi
  VERIFIED_AT="$(now_iso)"
  echo "验证完成：$VERIFIED_AT"
  [[ "$STOP_AFTER" == "verify" ]] && stop_here verify
  return 0
}

# ---------------------------------------------------------------------------
# 阶段 7：report（实测 RPO / RTO 报告落盘 + 存档；任一判定 FAIL 以非零退出）
# ---------------------------------------------------------------------------

phase_report() {
  phase_banner 7 report "演练报告（实测 RPO / RTO）落盘与存档"
  local declared_epoch verified_epoch rto verdict_rpo_in verdict_rto verdict_proofs verdict_objects
  local fail=0
  declared_epoch="$(iso_epoch "$DECLARED_TS")"
  verified_epoch="$(iso_epoch "$VERIFIED_AT")"
  rto=$(( verified_epoch - declared_epoch ))

  verdict_rpo_in="PASS"; (( RPO_IN_SECONDS <= 300 )) || { verdict_rpo_in="FAIL"; fail=1; }
  verdict_rpo_x="PASS"; (( RPO_X_SECONDS >= 0 )) || { verdict_rpo_x="FAIL"; fail=1; }
  verdict_rto="PASS"; (( rto <= 14400 )) || { verdict_rto="FAIL"; fail=1; }
  verdict_proofs="PASS"; (( PROOFS_FAILED == 0 )) || { verdict_proofs="FAIL"; fail=1; }
  verdict_objects="PASS"; [[ -z "$OBJECTS_MISSING" ]] || { verdict_objects="FAIL"; fail=1; }
  PROBLEMS_COUNT=$fail

  python3 - "$WORK_DIR/report.json" "$WORK_DIR/report.md" <<PY
import json
import sys
from pathlib import Path

data = {
    "drill_id": "$DRILL_ID",
    "task": "E22-T07",
    "mode": "$MODE",
    "region_failed": "$REGION_FAILED",
    "region_standby": "$REGION_STANDBY",
    "tenant_code": "$TENANT_CODE",
    "declared_at": "$DECLARED_TS",
    "last_restorable_in_region": "$LAST_RESTORABLE_IN",
    "last_restorable_cross_region": "$LAST_RESTORABLE_X",
    "milestones": {
        "inventory_done": "$INVENTORY_DONE",
        "rebuild_done": "$REBUILD_DONE",
        "restore_done": "$RESTORE_DONE",
        "switch_done": "$SWITCH_DONE",
        "verified_at": "$VERIFIED_AT",
    },
    "measured": {
        "in_region_rpo_seconds": $RPO_IN_SECONDS,
        "cross_region_rpo_seconds": $RPO_X_SECONDS,
        "rto_seconds": $rto,
    },
    "targets": {"in_region_rpo_seconds": 300, "rto_seconds": 14400},
    "verdicts": {
        "in_region_rpo": "$verdict_rpo_in",
        "cross_region_rpo_recorded": "$verdict_rpo_x",
        "rto": "$verdict_rto",
        "proofs": "$verdict_proofs",
        "objects": "$verdict_objects",
    },
    "checks": {
        "registry_rows_rewritten": $REGISTRY_REWRITTEN,
        "proofs_failed": $PROOFS_FAILED,
        "objects_missing": [k for k in """$OBJECTS_MISSING""".splitlines() if k],
        "proof_results": [l for l in """$PROOF_RESULTS""".splitlines() if l],
    },
}
Path(sys.argv[1]).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

fmt = lambda s: f"{s} 秒（{s / 60:.1f} 分钟）"
lines = [
    "# 跨区域恢复演练报告（E22-T07）",
    "",
    f"- 演练编号：{data['drill_id']}（模式 {data['mode']}）",
    f"- 场景：{data['region_failed']} 区域不可用 → {data['region_standby']} 重建恢复",
    f"- 数据核对租户：{data['tenant_code']}",
    f"- 宣告时刻（T0）：{data['declared_at']}",
    f"- 恢复点：区域内 {data['last_restorable_in_region']}；跨区域 {data['last_restorable_cross_region']}",
    "",
    "## 时间线（RTO = 宣告 → 验证完成）",
    "",
    "| 里程碑 | 时刻 |",
    "|---|---|",
]
for name, label in [
    ("declared_at", "宣告区域灾难（T0）"),
    ("inventory_done", "备份盘点与 RPO 实测"),
    ("rebuild_done", "备用区域重建完成"),
    ("restore_done", "恢复完成"),
    ("switch_done", "注册表 / 应用切换完成"),
    ("verified_at", "验证完成（服务恢复）"),
]:
    value = data["declared_at"] if name == "declared_at" else data["milestones"].get(name, "")
    lines.append(f"| {label} | {value} |")
lines += [
    "",
    "## 实测 RPO / RTO（验收：区域内 RPO ≤ 5 分钟，NFR-AVL-002）",
    "",
    "| 指标 | 目标 | 实测值 | 判定 |",
    "|---|---|---|---|",
    f"| 实测 RPO（区域内） | ≤ 300 秒（Binlog 每 5 分钟上传） | {fmt(data['measured']['in_region_rpo_seconds'])} | {data['verdicts']['in_region_rpo']} |",
    f"| 实测 RPO（跨区域） | 无 SLA，仅实测记录（异步复制） | {fmt(data['measured']['cross_region_rpo_seconds'])} | {data['verdicts']['cross_region_rpo_recorded']} |",
    f"| 实测 RTO | ≤ 4 小时（IaC 重建 + 恢复 + 切换 + 验证） | {fmt(data['measured']['rto_seconds'])} | {data['verdicts']['rto']} |",
    "",
    "## 验证清单",
    "",
    f"- 注册表改写：{data['checks']['registry_rows_rewritten']} 行 jdbc_url → 备用区域实例",
    f"- 验证清单（恢复点前三态标记等）：失败 {data['checks']['proofs_failed']} 项，判定 {data['verdicts']['proofs']}",
    f"- 对象对齐（恢复点之前的对象在备用区域副本）：判定 {data['verdicts']['objects']}"
    + ("，缺失：" + "、".join(data["checks"]["objects_missing"]) if data["checks"]["objects_missing"] else ""),
    "",
]
for row in data["checks"]["proof_results"]:
    lines.append("- " + row.replace("|", " · ", 1))
lines += [
    "",
    "## 结论",
    "",
    ("- 全部验收门 PASS：演练报告按 docs/ops/drill-records/ 规则存档（生产报告归档运维 wiki 并登记）。"
     if $fail == 0 else
     "- 存在 FAIL 项：按 runbook §4 处置后重新演练，本报告仍存档备查。"),
    "",
]
Path(sys.argv[2]).write_text("\n".join(lines), encoding="utf-8")
PY

  echo "报告已生成：$WORK_DIR/report.json / report.md"
  if (( fail == 0 )); then
    echo "结论：区域内 RPO ${RPO_IN_SECONDS}s ≤ 300s、RTO ${rto}s ≤ 14400s，全部验收门 PASS。"
  else
    echo "结论：存在 FAIL 项（见报告），演练未达标。" >&2
  fi
  if [[ -n "${REPORT_ARCHIVE_DIR:-}" ]]; then
    mkdir -p "$REPORT_ARCHIVE_DIR"
    cp "$WORK_DIR/report.md" "$REPORT_ARCHIVE_DIR/$DRILL_ID.md" \
      && echo "已存档：$REPORT_ARCHIVE_DIR/$DRILL_ID.md"
  fi
  (( fail == 0 )) || exit 1
  return 0
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

REPORT_ARCHIVE_DIR="${REPORT_ARCHIVE_DIR:-}"
phase_plan
phase_inventory
phase_rebuild
phase_restore
phase_switch
phase_verify
phase_report
