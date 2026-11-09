#!/usr/bin/env python3
"""医院 A 历史数据样本脱敏工具（E23-T05 · A-PAT-018 宽表样本对接，W34）。

医院提供的宽表 Excel（真实患者数据，AGENTS §5.5：**永不进入仓库**）先经本工具脱敏，
才能进测试环境走导入预检（验收「医院提供的样本经脱敏后校验通过」）。工具做两件事：

1. 版面核对（与后端 `WideLayoutAdapter` 同一口径）：「9 个前置列 + 212 个基线锚点列 +
   N 组 × 54 个随访锚点列」，总列数 = 221 + 54 × 组数；锚点来自迁移 V38（医院 A 字段
   字典，E23-T01）的「原表列名「…」」，列数或表头对不上整体报错、不做猜测；
2. 标识列替换：前置列「患者编号 / 病案号 / 姓名」与基线敏感锚点列（V38 sensitive_flag=1：
   病案号 / 姓名 / 电话_1 / 电话_2）替换为虚构值——同一列族内同一原值 → 同一虚构值
   （前置列与基线列保持一致，避免 E18-T03「前置列与基线列不一致」警告），非标识临床值
   原样保留（诊断、日期、检验值——预检要用的都在）。

虚构值全部带可辨识的虚构标记（姓名含「示例」、病案号 S 前缀、患者编号 L 前缀、
电话 1390000 示例号段），映射按 HMAC(salt) 排序后顺序编号：同 salt 同输入可复现、
不同原值必得不同编号（5000 名患者量级无碰撞）。运行手册见
`docs/ops/hospital-a-sample-intake.md`。

安全护栏：

1. 输入文件不得位于仓库内（真实样本不进仓库）；输出不得覆盖输入；
2. 映射表 `--map-out`（原值 ↔ 虚构值，用于追溯）只能写到仓库外，默认不写；
3. 工具不打印任何原值，只打印各列替换计数；salt 只用于映射，不落日志默认打印到
   stderr 供复现（salt 本身不泄露原值）；
4. 写完自检：重读输出文件，核对标识列无任何原值残留、行列形状不变、非标识单元格
   逐格一致，任何一条不满足即报错退出（输出文件视为无效）。

用法：
  python3 scripts/deidentify_sample.py 医院样本.xlsx -o 脱敏样本.xlsx [--salt S] [--map-out 映射.csv]
  python3 scripts/deidentify_sample.py --selftest   # CI / 本地自检（内置虚构样本，不依赖真实数据）

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import re
import secrets
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "backend/crdp-server/src/main/resources/db/tenant/followup/V38__hospital_a_field_dict.sql"

# 宽表前置列（A 规格 §4「医院现有宽表的列结构」，与 WideLayoutAdapter.FRONT_COLUMNS 同一口径）
FRONT_COLUMNS = ["序号", "患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类", "数据完整度"]

# 前置列中的标识列族（序号忽略、数据完整度不导入、性别/年龄/日期/诊断不标识到个人）
FRONT_FAMILIES = {"患者编号": "legacy", "病案号": "mrn", "姓名": "name"}

# V38 帮助说明里的原表列名锚点（gen_a_field_dict.py / ImportFieldCatalogLoader 同一约定）
ORIGINAL_HEADER_RE = re.compile(r"原表列名「(.+?)」")

# 姓氏池（虚构姓名 = 姓 +「示例」+ 序号；全部虚构，规则 5）
SURNAMES = "张王李赵刘陈杨黄周吴徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭"

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


class LayoutError(Exception):
    """版面 / 护栏问题：整体报错，不做猜测。"""


# ---------------------------------------------------------------- V38 字典解析


def _split_values(body: str) -> list[str]:
    """SQL VALUES 一行的顶层逗号切分（尊重引号与 '' 转义），返回去引号后的值。"""
    values: list[str] = []
    current: list[str] = []
    in_string = False
    i = 0
    while i < len(body):
        ch = body[i]
        if in_string:
            if ch == "'":
                if i + 1 < len(body) and body[i + 1] == "'":
                    current.append("'")
                    i += 1
                else:
                    in_string = False
            else:
                current.append(ch)
        else:
            if ch == "'":
                in_string = True
            elif ch == ",":
                values.append("".join(current).strip())
                current = []
            else:
                current.append(ch)
        i += 1
    values.append("".join(current).strip())
    return values


def _table_rows(sql: str, marker: str, arity: int) -> list[list[str]]:
    rows: list[list[str]] = []
    for line in sql.splitlines():
        stripped = line.strip()
        if not stripped.startswith(marker) or not (
            stripped.endswith("),") or stripped.endswith(");")
        ):
            continue
        body = stripped[stripped.index("(") + 1 : stripped.rindex(")")]
        values = _split_values(body)
        if len(values) == arity:
            rows.append(values)
    return rows


def load_layout_from_migration() -> tuple[list[str], list[str], dict[str, str]]:
    """从 V38 读宽表锚点布局：基线锚点列序、随访锚点列序、锚点 → 标识列族。

    基线 / 随访各按 sort_no 排回字典顺序（宽表列序 = 字典顺序）；sensitive_flag=1 且带
    锚点的字段进标识列族（mrn / name / phone）。
    """
    sql = MIGRATION.read_text(encoding="utf-8")
    fields = _table_rows(sql, "('A_", 20)
    if not fields:
        raise LayoutError(f"迁移脚本没有可解析的字段行：{MIGRATION}")
    # 列序：form, key, code, label, category, type, unit, decimals, hard, soft,
    #       option_set, required, visible_when, required_when, derived, sensitive, storage, help, sort_no, status
    fields.sort(key=lambda row: int(row[18]))
    baseline: list[str] = []
    followup: list[str] = []
    families: dict[str, str] = {}
    for row in fields:
        anchor_match = ORIGINAL_HEADER_RE.search(row[17] or "")
        if not anchor_match:
            continue
        anchor = anchor_match.group(1)
        if row[0] == "A_BASELINE":
            baseline.append(anchor)
            if row[15] == "1":
                families[anchor] = _baseline_family(row[3])
        else:
            followup.append(anchor)
    if len(baseline) != 212 or len(followup) != 54:
        raise LayoutError(
            f"V38 锚点列数不对：基线 {len(baseline)}（应 212）、随访 {len(followup)}（应 54）"
        )
    return baseline, followup, families


def _baseline_family(label: str) -> str:
    """基线敏感字段 → 列族（虚构值形态）；不在已知族的敏感字段按文本族处理（同姓替换）。"""
    if "病案号" in label:
        return "mrn"
    if "姓名" in label:
        return "name"
    if "电话" in label:
        return "phone"
    return "name"


# ---------------------------------------------------------------- xlsx 读写（口径与 StreamingXlsxGateway 一致：sharedStrings / inlineStr / 公式结果 / 数值都出文本）


def _column_index(ref: str) -> int:
    letters = re.match(r"([A-Z]+)", ref)
    if not letters:
        raise LayoutError(f"无效单元格引用：{ref}")
    index = 0
    for ch in letters.group(1):
        index = index * 26 + (ord(ch) - ord("A") + 1)
    return index - 1


def read_xlsx(data: bytes) -> list[list[str]]:
    """读第一张工作表为文本矩阵（行 × 列；空单元格为 ""；单工作表文件，护栏在版面核对）。"""
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as failure:
        raise LayoutError(f"不是有效的 xlsx 文件：{failure}") from failure
    with archive:
        names = archive.namelist()
        shared: list[str] = []
        if "xl/sharedStrings.xml" in names:
            shared = _read_shared_strings(archive.read("xl/sharedStrings.xml"))
        sheet_name = _first_sheet_part(archive)
        if sheet_name is None:
            raise LayoutError("xlsx 里没有工作表")
        rows: list[list[str]] = []
        root = ET.fromstring(archive.read(sheet_name))
        for row in root.iter(f"{NS}row"):
            cells: dict[int, str] = {}
            fallback = 0
            for cell in row.iter(f"{NS}c"):
                ref = cell.get("r")
                position = _column_index(ref) if ref else fallback
                fallback = position + 1
                cells[position] = _cell_text(cell, shared)
            width = max(cells) + 1 if cells else 0
            rows.append([cells.get(i, "") for i in range(width)])
        return rows


def _first_sheet_part(archive: zipfile.ZipFile) -> str | None:
    """workbook.xml 的第一张 sheet 对应的 part 路径（rId → rels → target）。"""
    main_ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    rel_ns = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
    package_ns = "{http://schemas.openxmlformats.org/package/2006/relationships}"
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    first = workbook.find(f"{main_ns}sheets/{main_ns}sheet")
    if first is None:
        return None
    rid = first.get(f"{rel_ns}id")
    rels = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    for rel in rels.findall(f"{package_ns}Relationship"):
        if rel.get("Id") == rid:
            target = rel.get("Target", "")
            part = target[1:] if target.startswith("/") else "xl/" + target
            return part if part in archive.namelist() else target
    return None


def _read_shared_strings(data: bytes) -> list[str]:
    strings: list[str] = []
    root = ET.fromstring(data)
    for item in root.iter(f"{NS}si"):
        parts = [node.text or "" for node in item.iter(f"{NS}t")]
        strings.append("".join(parts))
    return strings


def _cell_text(cell: ET.Element, shared: list[str]) -> str:
    kind = cell.get("t", "n")
    if kind == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(f"{NS}t"))
    value = cell.find(f"{NS}v")
    raw = "" if value is None else (value.text or "")
    if kind == "s":
        try:
            return shared[int(raw)]
        except (ValueError, IndexError):
            return ""
    return raw


def write_xlsx(rows: list[list[str]], sheet_name: str = "患者数据") -> bytes:
    """写最小 xlsx（单工作表、inlineStr 全文本单元格——StreamingImportWorkbook 可读）。"""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zip_file:
        zip_file.writestr(
            "[Content_Types].xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            "</Types>",
        )
        zip_file.writestr(
            "_rels/.rels",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            "</Relationships>",
        )
        zip_file.writestr(
            "xl/workbook.xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            f'<sheets><sheet name="{_xml_escape(sheet_name)}" sheetId="1" r:id="rId1"/></sheets></workbook>',
        )
        zip_file.writestr(
            "xl/_rels/workbook.xml.rels",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            "</Relationships>",
        )
        body = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>', f"<worksheet xmlns=\"{NS[1:-1]}\"><sheetData>"]
        for row_no, row in enumerate(rows, start=1):
            body.append(f'<row r="{row_no}">')
            for col_no, value in enumerate(row):
                if value == "":
                    continue  # 稀疏写：空单元格省略，读取侧按表头宽度补齐
                body.append(
                    f'<c r="{_column_letters(col_no)}{row_no}" t="inlineStr"><is><t xml:space="preserve">'
                    f"{_xml_escape(value)}</t></is></c>"
                )
            body.append("</row>")
        body.append("</sheetData></worksheet>")
        zip_file.writestr("xl/worksheets/sheet1.xml", "".join(body))
    return buffer.getvalue()


def _column_letters(zero_based: int) -> str:
    index = zero_based + 1
    letters = ""
    while index > 0:
        index, digit = divmod(index - 1, 26)
        letters = chr(ord("A") + digit) + letters
    return letters


def _xml_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


# ---------------------------------------------------------------- 版面核对与替换


def check_layout(rows: list[list[str]]) -> tuple[int, dict[int, str]]:
    """表头按位置对位（前置 9 + 基线 212 + N 组 × 54），返回（组数，列号 → 列族）。

    列数或表头对不上整体报错（与 WideLayoutAdapter 同一「不做猜测」口径）。
    """
    baseline, followup, families = load_layout_from_migration()
    problems: list[str] = []
    if not rows:
        raise LayoutError("工作表为空，没有表头行")
    header = rows[0]
    for i, expected in enumerate(FRONT_COLUMNS):
        actual = header[i].strip() if i < len(header) else ""
        if actual != expected:
            problems.append(f"第 {i + 1} 列（{_column_letters(i)}）表头「{actual}」应为前置列「{expected}」")
    for i, expected in enumerate(baseline):
        position = len(FRONT_COLUMNS) + i
        actual = header[position].strip() if position < len(header) else ""
        if actual != expected:
            problems.append(
                f"第 {position + 1} 列（{_column_letters(position)}）表头「{actual}」应为基线列「{expected}」"
            )
    followup_start = len(FRONT_COLUMNS) + len(baseline)
    remainder = len(header) - followup_start
    if remainder <= 0 or remainder % len(followup) != 0:
        problems.append(
            f"随访列组列数不对：总列数 {len(header)}，应为 {followup_start} + {len(followup)} × 组数"
        )
        groups = 0
    else:
        groups = remainder // len(followup)
        for g in range(groups):
            for j, expected in enumerate(followup):
                position = followup_start + g * len(followup) + j
                actual = header[position].strip()
                if actual != expected:
                    problems.append(f"第 {g + 1} 组随访第 {position + 1} 列表头「{actual}」应为「{expected}」")
    if problems:
        raise LayoutError(f"宽表表头不匹配（{len(problems)} 处，整体报错不做猜测）：\n" + "\n".join(problems[:10]))
    columns: dict[int, str] = {}
    for name, family in FRONT_FAMILIES.items():
        columns[FRONT_COLUMNS.index(name)] = family
    for anchor, family in families.items():
        columns[len(FRONT_COLUMNS) + baseline.index(anchor)] = family
    return groups, columns


class Fictionalizer:
    """列族内原值 → 虚构值：HMAC(salt) 排序后按出现序编号（无碰撞、同 salt 可复现）。"""

    def __init__(self, salt: str):
        self.salt = salt.encode("utf-8")
        self.seen: dict[tuple[str, str], str] = {}
        self.ranks: dict[str, list[str]] = {}

    def _rank(self, family: str, value: str) -> int:
        digest = hmac.new(self.salt, f"{family}:{value}".encode("utf-8"), hashlib.sha256).digest()
        return int.from_bytes(digest[:8], "big")

    def map(self, family: str, value: str) -> str:
        if not value.strip() or value.strip() == "N/A":
            return value  # 空白与 N/A（导入侧归一为空值，精确匹配不做大小写猜测）不是标识值，原样保留
        key = (family, value)
        if key not in self.seen:
            pool = self.ranks.setdefault(family, [])
            pool.append(value)
            pool.sort(key=lambda item: self._rank(family, item))
            for index, item in enumerate(pool, start=1):
                self.seen[(family, item)] = self._format(family, index)
        return self.seen[key]

    @staticmethod
    def _format(family: str, index: int) -> str:
        if family == "mrn":
            return f"S{index:06d}"
        if family == "legacy":
            return f"L{index:06d}"
        if family == "phone":
            return f"1390000{index % 10000:04d}"
        surname = SURNAMES[index % len(SURNAMES)]
        return f"{surname}示例{index % 100:02d}"


def deidentify(rows: list[list[str]], salt: str) -> tuple[list[list[str]], dict[str, int], dict[str, str]]:
    """整表脱敏：返回（新行集，各列族替换计数，原值 → 虚构值映射）。"""
    groups, columns = check_layout(rows)
    fictionalizer = Fictionalizer(salt)
    width = len(rows[0])
    output: list[list[str]] = [rows[0]]
    counts: dict[str, int] = {}
    for row in rows[1:]:
        cells = list(row) + [""] * (width - len(row))
        for position, family in columns.items():
            original = cells[position]
            replaced = fictionalizer.map(family, original)
            if replaced != original:
                counts[family] = counts.get(family, 0) + 1
                cells[position] = replaced
        output.append(cells)
    mapping = {f"{family}\t{original}": replaced for (family, original), replaced in fictionalizer.seen.items()}
    return output, counts, mapping


def selfcheck(rows: list[list[str]], output: list[list[str]], mapping: dict[str, str]) -> None:
    """写后自检：形状不变、非标识单元格逐格一致、标识列无原值残留。"""
    if len(output) != len(rows) or len(output[0]) != len(rows[0]):
        raise LayoutError("脱敏前后行列形状变化")
    _, columns = check_layout(rows)
    replaced_by_column = {position for position, _family in columns.items()}
    originals = set()
    for original_key in mapping:
        originals.add(original_key.split("\t", 1)[1])
    for row_no, (before, after) in enumerate(zip(rows[1:], output[1:]), start=2):
        for col_no, (old, new) in enumerate(zip(before, after)):
            if col_no in replaced_by_column:
                if old.strip() and old.strip() != "N/A" and new == old:
                    raise LayoutError(f"第 {row_no} 行第 {col_no + 1} 列标识值未替换")
            elif old != new:
                raise LayoutError(f"第 {row_no} 行第 {col_no + 1} 列非标识单元格被改动")
            if new in originals and (col_no in replaced_by_column):
                raise LayoutError(f"第 {row_no} 行第 {col_no + 1} 列残留原值")


# ---------------------------------------------------------------- 命令行与自检


def _guard_paths(source: Path, output: Path, map_out: Path | None) -> None:
    source = source.resolve()
    if source == ROOT.resolve() or ROOT.resolve() in source.parents:
        raise LayoutError(f"输入文件在仓库内（{source}）：医院真实样本不得进入仓库（AGENTS §5.5）")
    if source == output.resolve():
        raise LayoutError("输出文件不能覆盖输入文件")
    if map_out is not None and (map_out.resolve() == ROOT.resolve() or ROOT.resolve() in map_out.resolve().parents):
        raise LayoutError(f"映射表在仓库内（{map_out}）：原值 ↔ 虚构值映射不得进入仓库（AGENTS §5.5）")


def run(source: Path, output: Path, salt: str, map_out: Path | None) -> None:
    _guard_paths(source, output, map_out)
    rows = read_xlsx(source.read_bytes())
    deidentified, counts, mapping = deidentify(rows, salt)
    selfcheck(rows, deidentified, mapping)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(write_xlsx(deidentified))
    groups, _ = check_layout(rows)
    print(f"脱敏完成：{len(rows) - 1} 行 × {len(rows[0])} 列（{groups} 组随访），输出 {output}")
    for family, count in sorted(counts.items()):
        print(f"  {family}: {count} 个标识值已替换为虚构值")
    if map_out is not None:
        lines = ["family\toriginal\tfictional"]
        lines += [f"{key.split(chr(9), 1)[0]}\t{key.split(chr(9), 1)[1]}\t{value}" for key, value in sorted(mapping.items())]
        map_out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"  映射表（仓库外留存）：{map_out}")
    print(f"  salt：{salt}（留存于本次运行记录，配合同一输入可复现映射）", file=sys.stderr)


def selftest() -> None:
    """CI / 本地自检：内置虚构样本走全流程，断言替换、一致性、护栏与自检。"""
    baseline, followup, families = load_layout_from_migration()
    assert len(baseline) == 212 and len(followup) == 54, (len(baseline), len(followup))
    assert families == {"病案号": "mrn", "姓名": "name", "电话_1": "phone", "电话_2": "phone"}, families

    header = list(FRONT_COLUMNS) + baseline + followup  # 1 组随访即 275 列
    def put(row: dict[int, str]) -> list[str]:
        return [row.get(i, "") for i in range(len(header))]

    mrn_col = len(FRONT_COLUMNS) + baseline.index("病案号")
    name_col = len(FRONT_COLUMNS) + baseline.index("姓名")
    phone_col = len(FRONT_COLUMNS) + baseline.index("电话_1")
    date_col = len(FRONT_COLUMNS) + baseline.index("手术日期")
    rows = [
        header,
        put({1: "P0001", 2: "0001234", 3: "样例一（示例）", 4: "男", 5: "66", 6: "20260101", 7: "冠心病",
             mrn_col: "0001234", name_col: "样例一（示例）", phone_col: "13800000001", date_col: "20260110"}),
        put({1: "P0002", 2: "0001235", 3: "样例二（示例）", 4: "女", 5: "58",
             mrn_col: "0001235", name_col: "样例二（示例）", phone_col: "N/A", date_col: "20260211"}),
        put({2: "0001234", mrn_col: "0001234", name_col: "样例一（示例）"}),  # 与第 1 行同一患者（重复病案号）
    ]

    output, counts, mapping = deidentify(rows, "selftest-salt")
    selfcheck(rows, output, mapping)
    # ① 标识值全部替换且形态正确：病案号 S 前缀、患者编号 L 前缀、姓名带「示例」、电话示例号段
    assert output[1][2] == output[3][2] == output[1][mrn_col] == output[3][mrn_col], "前置列与基线列病案号应保持一致"
    assert re.fullmatch(r"S\d{6}", output[1][2]), output[1][2]
    assert re.fullmatch(r"L\d{6}", output[1][1]), output[1][1]
    assert "示例" in output[1][name_col], output[1][name_col]
    assert re.fullmatch(r"139\d{8}", output[1][phone_col]), output[1][phone_col]
    # ② 同一原值 → 同一虚构值；不同原值 → 不同虚构值（重复病案号仍是同一患者）
    assert output[1][name_col] == output[3][name_col] and output[1][name_col] != output[2][name_col]
    assert output[1][2] != output[2][2]
    # ③ 非标识临床值原样保留；N/A 不是标识值、原样保留（导入侧归一为空）
    assert output[1][4] == "男" and output[1][date_col] == "20260110" and output[2][phone_col] == "N/A"
    # ④ 同 salt 可复现；salt 参与排序（不同 salt 的排序键必不同——小值池下编号仍可能相同，不硬断言）
    again, _, _ = deidentify(rows, "selftest-salt")
    assert again == output, "同 salt 同输入应得到同一输出"
    assert (
        Fictionalizer("salt-a")._rank("mrn", "0001234") != Fictionalizer("salt-b")._rank("mrn", "0001234")
    ), "salt 应参与虚构编号的排序键"
    # ⑤ 原值不残留（全表搜索）
    for row in output[1:]:
        for cell in row:
            assert cell not in {"0001234", "0001235", "样例一（示例）", "样例二（示例）", "13800000001", "P0001", "P0002"}
    # ⑥ xlsx 往返：写出的文件读回仍是脱敏后的形状（StreamingImportWorkbook 同口径；
    #    稀疏行读回只到该行最后一个非空单元格，逐格比对已存在的部分）
    reread = read_xlsx(write_xlsx(output))
    assert reread[0] == output[0], "表头往返应一致"
    for row_no in (1, 2, 3):
        width = len(reread[row_no])
        assert reread[row_no] == output[row_no][:width], f"第 {row_no + 1} 行往返应一致"
    # ⑦ 表头被改 → 整体报错不做猜测
    tampered = [list(header), list(rows[1])]
    tampered[0][0] = "编号"
    try:
        deidentify(tampered, "selftest-salt")
    except LayoutError as failure:
        assert "前置列" in str(failure), failure
    else:
        raise AssertionError("表头不匹配应整体报错")
    # ⑧ 护栏：输入 / 映射表在仓库内拒绝，输出覆盖输入拒绝
    inside = ROOT / "samples" / "医院样本.xlsx"
    try:
        _guard_paths(inside, Path("/tmp/out.xlsx"), None)
    except LayoutError:
        pass
    else:
        raise AssertionError("仓库内输入应被拒绝")
    try:
        _guard_paths(Path("/tmp/in.xlsx"), Path("/tmp/in.xlsx"), ROOT / "map.csv")
    except LayoutError:
        pass
    else:
        raise AssertionError("仓库内映射表 / 输出覆盖输入应被拒绝")
    # ⑨ 计数口径：mrn 6 处（3 行 × 前置 + 基线）、name 5 处（第 3 行姓名也在前置与基线两列）、
    # legacy 2 处（仅前置列）、phone 1 处（N/A 不是标识值不计）
    assert counts == {"mrn": 6, "legacy": 2, "name": 5, "phone": 1}, counts
    print("selftest 通过：版面核对、标识替换、一致性、复现、护栏、写后自检全部符合预期")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="医院 A 宽表历史样本脱敏（E23-T05 / A-PAT-018）")
    parser.add_argument("source", nargs="?", type=Path, help="医院提供的宽表 xlsx（仓库外）")
    parser.add_argument("-o", "--output", type=Path, help="脱敏后 xlsx 输出路径")
    parser.add_argument("--salt", default=secrets.token_hex(8), help="映射盐（复现同一映射用；默认随机）")
    parser.add_argument("--map-out", type=Path, default=None, help="原值 ↔ 虚构值映射表路径（仅仓库外，用于追溯）")
    parser.add_argument("--selftest", action="store_true", help="内置虚构样本自检（CI 用）")
    args = parser.parse_args(argv)
    if args.selftest:
        selftest()
        return 0
    if args.source is None or args.output is None:
        parser.error("需要 source 与 -o/--output（或 --selftest）")
    try:
        run(args.source, args.output, args.salt, args.map_out)
    except LayoutError as failure:
        print(f"脱敏失败：{failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
