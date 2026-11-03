/**
 * E13-T06 / A-PAT-014、A-PAT-015、A-NFR-009、C-PAT-013：高级检索条件构造器与保存的个人筛选的
 * 类型与纯逻辑（接口契约 docs/api/patient.md §1，后端 `PatientSearchController` /
 * `SavedFilterController`）。
 *
 * 条件树为结构化 JSON（components.md §11.2 ConditionGroup）：后端只接收结构化条件并编译为
 * 参数化查询（字段必须出现在 search-fields 目录 = 字段字典生成），**不允许原始 SQL**；
 * 病区条件不进条件树——后端数据范围自动追加（A-NFR-009「自动追加病区条件」）。
 */

/** 高级检索可用字段（GET /patients/search-fields 下发；field = 字段字典 field_code）。 */
export interface AdvancedSearchField {
  categoryKey: string;
  categoryLabel: string;
  common: boolean;
  field: string;
  label: string;
  /** 取值形态：DATE / NUMBER / TEXT / OPTION（决定值控件）。 */
  ops: string[];
  options: AdvancedOptionValue[];
  type: 'DATE' | 'NUMBER' | 'OPTION' | 'TEXT';
  unit?: null | string;
}

export interface AdvancedOptionValue {
  label: string;
  /** 数据库存储编码：性别 M/F、病区 id、出院状态 1–3。 */
  value: number | string;
}

/** 运算符文案（A-PAT-014：等于、不等于、大于、小于、介于、包含、为空、不为空）。 */
export const OPERATOR_LABELS: Record<string, string> = {
  BETWEEN: '介于',
  CONTAINS: '包含',
  EQ: '等于',
  GT: '大于',
  IS_NULL: '为空',
  LT: '小于',
  NE: '不等于',
  NOT_NULL: '不为空',
};

/** 不需要取值的运算符（为空 / 不为空）。 */
export const NULL_OPS = new Set(['IS_NULL', 'NOT_NULL']);

/** 条件行（op 取 OPERATOR_LABELS 的键；BETWEEN 的 value 为二元数组）。 */
export interface ConditionRow {
  field: string;
  op: string;
  value?: Array<number | string> | null | number | string;
}

/** 条件组（行与子组混排；logic 缺省 AND）。 */
export interface ConditionGroup {
  items: Array<ConditionGroup | ConditionRow>;
  logic: 'and' | 'or';
}

/** 保存的个人筛选（A-PAT-015，仅本人可见）。 */
export interface SavedFilter {
  id: string;
  name: string;
  /** 检索条件 {filters?, condition?}（不含检索词）。 */
  payload: SavedFilterPayload;
  updatedAt: string;
}

export interface SavedFilterPayload {
  condition?: ConditionGroup;
  filters?: Record<string, unknown>;
}

// ---------------------------------------------------------------- 构造与判定

export function isConditionGroup(
  item: ConditionGroup | ConditionRow,
): item is ConditionGroup {
  return 'items' in item;
}

export function emptyConditionGroup(): ConditionGroup {
  return { logic: 'and', items: [] };
}

export function emptyConditionRow(fields: AdvancedSearchField[]): ConditionRow {
  const first = fields[0];
  const op = first ? (first.ops[0] ?? 'EQ') : 'EQ';
  return { field: first?.field ?? '', op, value: null };
}

function rowValueNeeded(op: string): boolean {
  return !NULL_OPS.has(op);
}

/** 行是否完整（字段在目录中、运算符、取值齐备；BETWEEN 须二元数组）。 */
export function isRowComplete(
  row: ConditionRow,
  fields: AdvancedSearchField[],
): boolean {
  if (!row.field || !row.op) return false;
  if (!fields.some((item) => item.field === row.field)) return false;
  if (!rowValueNeeded(row.op)) return true;
  if (row.op === 'BETWEEN') {
    return (
      Array.isArray(row.value) &&
      row.value.length === 2 &&
      row.value.every(
        (item) => item !== null && item !== undefined && item !== '',
      )
    );
  }
  return row.value !== null && row.value !== undefined && row.value !== '';
}

/**
 * 剪掉不完整条件行与空组后的条件树（提交前调用；后端对目录外 / 缺项条件 40001，
 * 空组不发送）。根组为空返回 null（无高级条件）。
 */
export function pruneConditionGroup(
  group: ConditionGroup,
  fields: AdvancedSearchField[],
): ConditionGroup | null {
  const items: Array<ConditionGroup | ConditionRow> = [];
  for (const item of group.items) {
    if (isConditionGroup(item)) {
      const pruned = pruneConditionGroup(item, fields);
      if (pruned) items.push(pruned);
    } else if (isRowComplete(item, fields)) {
      items.push(item);
    }
  }
  if (items.length === 0) return null;
  return { logic: group.logic, items };
}

/** 全部行（含子组）是否完整（决定「查询」按钮可用）。 */
export function isConditionGroupComplete(
  group: ConditionGroup,
  fields: AdvancedSearchField[],
): boolean {
  return group.items.every((item) =>
    isConditionGroup(item)
      ? isConditionGroupComplete(item, fields)
      : isRowComplete(item, fields),
  );
}

// ---------------------------------------------------------------- 逻辑表达式预览（A-PAT-014）

function rowValueText(
  row: ConditionRow,
  field: AdvancedSearchField | undefined,
): string {
  const value = row.value;
  if (row.op === 'BETWEEN') {
    const [from, to] = Array.isArray(value) ? value : [null, null];
    return `${valueText(from, field)} ~ ${valueText(to, field)}`;
  }
  return valueText(value, field);
}

function valueText(
  value: unknown,
  field: AdvancedSearchField | undefined,
): string {
  if (value === null || value === undefined || value === '') return '…';
  const option = field?.options.find((item) => item.value === value);
  return option ? option.label : String(value);
}

/** 行文本：字段名 运算符 取值（取值显示选项文案 / 原值；缺省显示 …）。 */
export function conditionRowText(
  row: ConditionRow,
  fields: AdvancedSearchField[],
): string {
  const field = fields.find((item) => item.field === row.field);
  const opText = OPERATOR_LABELS[row.op] ?? row.op;
  const name = field ? field.label : row.field || '…';
  if (NULL_OPS.has(row.op)) return `${name} ${opText}`;
  return `${name} ${opText} ${rowValueText(row, field)}`;
}

/** 逻辑表达式预览：如（病区 = 示例一病区 并且 出院日期 介于 … ~ …）或者 性别 等于 女。 */
export function conditionPreview(
  group: ConditionGroup,
  fields: AdvancedSearchField[],
): string {
  const pruned = pruneConditionGroup(group, fields);
  if (!pruned) return '';
  return renderGroup(pruned, fields, false);
}

function renderGroup(
  group: ConditionGroup,
  fields: AdvancedSearchField[],
  wrap: boolean,
): string {
  const logicText = group.logic === 'or' ? '或者' : '并且';
  const parts = group.items.map((item) =>
    isConditionGroup(item)
      ? renderGroup(item, fields, true)
      : conditionRowText(item, fields),
  );
  if (parts.length === 1) return parts[0] ?? '';
  // 规格示例口径：右括号后不加空格（「…）或者 …」），行与行之间用空格
  const joined = parts
    .join(` ${logicText} `)
    .replaceAll(`） ${logicText}`, `）${logicText}`);
  return wrap ? `（${joined}）` : joined;
}

// ---------------------------------------------------------------- 条件数摘要（列表页提示条）

/** 生效条件行数（列表页「高级检索（n 项）」提示条）。 */
export function conditionRowCount(
  group: ConditionGroup,
  fields: AdvancedSearchField[],
): number {
  const pruned = pruneConditionGroup(group, fields);
  if (!pruned) return 0;
  return pruned.items.reduce(
    (count, item) =>
      count + (isConditionGroup(item) ? conditionRowCount(item, fields) : 1),
    0,
  );
}

// ---------------------------------------------------------------- 不可变更新（组件用）

export function withRowAdded(
  group: ConditionGroup,
  row: ConditionRow,
): ConditionGroup {
  return { ...group, items: [...group.items, row] };
}

export function withGroupAdded(group: ConditionGroup): ConditionGroup {
  return { ...group, items: [...group.items, emptyConditionGroup()] };
}

/** 替换指定路径上的条目（path 为组在树中的下标路径）。 */
export function withItemReplaced(
  group: ConditionGroup,
  path: number[],
  replacer: (
    item: ConditionGroup | ConditionRow,
  ) => ConditionGroup | ConditionRow,
): ConditionGroup {
  if (path.length === 0) return group;
  const [head, ...rest] = path;
  if (head === undefined) return group;
  const items = group.items.map((item, index) => {
    if (index !== head) return item;
    if (rest.length === 0) return replacer(item);
    if (!isConditionGroup(item)) return item;
    return withItemReplaced(item, rest, replacer);
  });
  return { ...group, items };
}

export function withItemRemoved(
  group: ConditionGroup,
  path: number[],
): ConditionGroup {
  if (path.length === 0) return group;
  const [head, ...rest] = path;
  if (head === undefined) return group;
  if (rest.length > 0) {
    return withItemReplaced(group, [head], (item) =>
      isConditionGroup(item) ? withItemRemoved(item, rest) : item,
    );
  }
  return { ...group, items: group.items.filter((_, index) => index !== head) };
}

/** 条件行 / 组在同级内移动（拖动排序落到不可变更新）。 */
export function withItemMoved(
  group: ConditionGroup,
  from: number,
  to: number,
): ConditionGroup {
  if (from === to || from < 0 || to < 0 || from >= group.items.length) {
    return group;
  }
  const items = [...group.items];
  const [moved] = items.splice(from, 1);
  if (moved === undefined) return group;
  items.splice(to, 0, moved);
  return { ...group, items };
}
