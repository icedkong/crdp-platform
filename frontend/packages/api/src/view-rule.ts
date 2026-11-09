/**
 * E20-T02 / PLT-VIEW-007、008、009：平台视角规则的前端类型与纯逻辑，
 * 与后端 `com.crdp.server.platform.ViewRuleService` / `ViewRuleResolver` 视图同构
 * （docs/api/platform.md §6）。匹配口径（已确认）：字段 > 模块 > 租户 > 全局默认，
 * 越具体越优先；同层多条命中（如账号有多个平台角色）按更严合并——
 * 隐藏 > 设盲 > 脱敏 > 明文、只读 > 可编辑、禁止导出 > 允许导出。
 * 效果预览与实际执行共用后端同一解析器，预览值即执行值（验收口径）。
 */

/** 规则层级（匹配优先级：字段 > 模块 > 租户 > 全局默认）。 */
export type ViewRuleLayer = 'FIELD' | 'GLOBAL' | 'MODULE' | 'TENANT';

/** 可见性。 */
export type ViewRuleVisibility = 'BLINDED' | 'HIDDEN' | 'MASKED' | 'PLAIN';

/** 操作。 */
export type ViewRuleOperation = 'EDITABLE' | 'READONLY';

/** 状态：生效 / 停用 / 待复核（E20-T05 / PLT-VIEW-014，新建即高敏的规则行初始状态，复核通过才生效）。 */
export type ViewRuleStatus = 'ACTIVE' | 'DISABLED' | 'PENDING_REVIEW';

/** 层级选项（配置页左栏顺序：全局默认 → 租户 → 模块 → 字段）。 */
export const VIEW_RULE_LAYER_OPTIONS: {
  hint: string;
  label: string;
  value: ViewRuleLayer;
}[] = [
  {
    label: '全局默认',
    value: 'GLOBAL',
    hint: '全部租户、模块与字段的兜底规则',
  },
  { label: '租户', value: 'TENANT', hint: '指定租户整体（可选收紧单家医院）' },
  {
    label: '模块',
    value: 'MODULE',
    hint: '配置类 / 业务数据等模块（可选限定租户）',
  },
  { label: '字段', value: 'FIELD', hint: '目录内的资源对象与字段（最优先）' },
];

/** 层级文案。 */
export function viewRuleLayerText(layer: null | string): string {
  return (
    VIEW_RULE_LAYER_OPTIONS.find((option) => option.value === layer)?.label ??
    layer ??
    '—'
  );
}

/** 可见性选项。 */
export const VIEW_RULE_VISIBILITY_OPTIONS: {
  label: string;
  value: ViewRuleVisibility;
}[] = [
  { label: '明文', value: 'PLAIN' },
  { label: '脱敏', value: 'MASKED' },
  { label: '设盲', value: 'BLINDED' },
  { label: '隐藏', value: 'HIDDEN' },
];

/** 可见性文案。 */
export function viewRuleVisibilityText(visibility: null | string): string {
  return (
    VIEW_RULE_VISIBILITY_OPTIONS.find((option) => option.value === visibility)
      ?.label ??
    visibility ??
    '—'
  );
}

/** 操作文案。 */
export function viewRuleOperationText(operation: null | string): string {
  switch (operation) {
    case 'EDITABLE': {
      return '可编辑';
    }
    case 'READONLY': {
      return '只读';
    }
    default: {
      return operation ?? '—';
    }
  }
}

/** 状态文案。 */
export function viewRuleStatusText(status: null | string): string {
  switch (status) {
    case 'ACTIVE': {
      return '生效';
    }
    case 'DISABLED': {
      return '停用';
    }
    case 'PENDING_REVIEW': {
      return '待复核';
    }
    default: {
      return status ?? '—';
    }
  }
}

/** 状态标签颜色（StatusTag 语义：生效绿 / 待复核橙 / 停用灰）。 */
export function viewRuleStatusColor(status: null | string): string {
  switch (status) {
    case 'ACTIVE': {
      return 'success';
    }
    case 'PENDING_REVIEW': {
      return 'warning';
    }
    default: {
      return 'default';
    }
  }
}

/** 脱敏规则码文案（沿用第 4 章脱敏规则；maskRuleText 用于选项与预览）。 */
export const VIEW_RULE_MASK_OPTIONS: { label: string; value: string }[] = [
  { label: '姓名（首尾保留）', value: 'name' },
  { label: '手机号（前 3 后 4）', value: 'phone' },
  { label: '证件号（前 6 后 4）', value: 'idCard' },
  { label: '病案号 / 病历号（前 2 后 2）', value: 'medicalRecordNo' },
  { label: '住址（前 6 字）', value: 'address' },
  { label: '通用（首尾保留）', value: 'generic' },
];

/** 脱敏码文案。 */
export function viewRuleMaskText(maskRule: null | string): string {
  return (
    VIEW_RULE_MASK_OPTIONS.find((option) => option.value === maskRule)?.label ??
    maskRule ??
    ''
  );
}

/** 规则行（列表与详情；roleCodes 为空 = 全部平台角色；pendingChange = 存在待复核变更）。 */
export interface ViewRuleItem {
  allowExport: boolean;
  code: string;
  field: null | string;
  fieldLabel: null | string;
  id: number;
  layer: ViewRuleLayer;
  maskRule: null | string;
  moduleCode: null | string;
  moduleName: null | string;
  name: string;
  operation: ViewRuleOperation;
  pendingChange: boolean;
  resource: null | string;
  resourceLabel: null | string;
  roleCodes: string[];
  status: ViewRuleStatus;
  tenantCode: null | string;
  tenantId: null | number;
  tenantName: null | string;
  updatedAt: string;
  version: number;
  visibility: ViewRuleVisibility;
}

/** 新建 / 维护请求。 */
export interface ViewRuleSaveRequest {
  allowExport: boolean;
  field?: null | string;
  layer: ViewRuleLayer;
  maskRule?: null | string;
  moduleCode?: null | string;
  name: string;
  operation: ViewRuleOperation;
  resource?: null | string;
  roleIds: number[];
  status?: ViewRuleStatus;
  tenantId?: null | number;
  visibility: ViewRuleVisibility;
}

/** 视角字段目录项。 */
export interface ViewRuleCatalogField {
  field: null | string;
  key: string;
  label: string;
  moduleCode: null | string;
  resource: string;
}

/** 目录（FIELD 层引用与预览用）。 */
export interface ViewRuleCatalog {
  fields: ViewRuleCatalogField[];
  maskRules: string[];
}

/** 命中规则回显（预览「由哪条规则决定 / 哪些被覆盖」）。 */
export interface ViewRuleTrace {
  code: string;
  id: number;
  layer: ViewRuleLayer;
  name: string;
}

/** 模块（页面）级效果。 */
export interface ViewRuleModuleEffect {
  allowExport: boolean;
  maskRule: null | string;
  matched: ViewRuleTrace[];
  moduleCode: string;
  moduleName: string;
  operation: ViewRuleOperation;
  overridden: ViewRuleTrace[];
  visibility: ViewRuleVisibility;
}

/** 字段级效果。 */
export interface ViewRuleFieldEffect {
  allowExport: boolean;
  field: null | string;
  label: string;
  maskRule: null | string;
  matched: ViewRuleTrace[];
  moduleCode: null | string;
  operation: ViewRuleOperation;
  overridden: ViewRuleTrace[];
  resource: string;
  visibility: ViewRuleVisibility;
}

/** 效果预览结果。 */
export interface ViewRulePreviewResult {
  fields: ViewRuleFieldEffect[];
  modules: ViewRuleModuleEffect[];
  roleCodes: string[];
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 效果预览请求：平台角色（多选，非空）+ 租户。 */
export interface ViewRulePreviewRequest {
  roleIds: number[];
  tenantId: number;
}

// ---------------------------------------------------------------- 高敏复核与版本链（E20-T05 / PLT-VIEW-014、015）

/** 保存结果（新建 / 维护 / 回滚）：规则行当前视图 + 是否进入待复核（高敏变更规则保持原状）。 */
export interface ViewRuleSaveOutcome {
  pendingReview: boolean;
  pendingVersionId: number;
  rule: ViewRuleItem;
}

/** 版本内容快照（前后值；roleIds 落库回放、roleCodes 展示）。 */
export interface ViewRuleVersionContent {
  allowExport: boolean;
  field: null | string;
  layer: ViewRuleLayer;
  maskRule: null | string;
  moduleCode: null | string;
  name: string;
  operation: ViewRuleOperation;
  resource: null | string;
  roleCodes: string[];
  roleIds: number[];
  status: ViewRuleStatus;
  tenantId: null | number;
  visibility: ViewRuleVisibility;
}

/** 版本行（版本记录与待复核列表；PLT-VIEW-015 记录变更人、前后值与复核人）。 */
export interface ViewRuleVersionItem {
  afterJson: string;
  appliedAt: null | string;
  beforeJson: null | string;
  changeType: 'CREATE' | 'ROLLBACK' | 'UPDATE';
  id: number;
  proposedAt: string;
  proposedByName: null | string;
  reviewComment: null | string;
  reviewedAt: null | string;
  reviewedByName: null | string;
  ruleCode: string;
  ruleId: number;
  ruleName: string;
  sensitive: boolean;
  status: 'APPLIED' | 'PENDING_REVIEW' | 'REJECTED';
  version: number;
}

/** 变更类型文案（CREATE 新建 / UPDATE 维护（含停用启用）/ ROLLBACK 回滚）。 */
export function viewRuleChangeTypeText(changeType: null | string): string {
  switch (changeType) {
    case 'CREATE': {
      return '新建';
    }
    case 'ROLLBACK': {
      return '回滚';
    }
    case 'UPDATE': {
      return '维护';
    }
    default: {
      return changeType ?? '—';
    }
  }
}

/** 版本行状态文案（待复核 / 已生效 / 已驳回）。 */
export function viewRuleVersionStatusText(status: null | string): string {
  switch (status) {
    case 'APPLIED': {
      return '已生效';
    }
    case 'PENDING_REVIEW': {
      return '待复核';
    }
    case 'REJECTED': {
      return '已驳回';
    }
    default: {
      return status ?? '—';
    }
  }
}

/** 版本行状态标签颜色。 */
export function viewRuleVersionStatusColor(status: null | string): string {
  switch (status) {
    case 'APPLIED': {
      return 'success';
    }
    case 'PENDING_REVIEW': {
      return 'warning';
    }
    default: {
      return 'error';
    }
  }
}

/** 高敏原因摘要（提交前提示与版本行说明同口径，OQ-99 ③）。 */
export function viewRuleSensitivityText(reasons: string[]): string {
  return reasons.length === 0
    ? ''
    : `高敏变更（${reasons.join('、')}），提交后需另一名平台管理员复核通过才生效`;
}

/**
 * 高敏判定（前端先行提示，与后端 ViewRuleReviewPolicy 同口径）：以「行的生效内容」按严格序
 * 比较——可见性 隐藏>设盲>脱敏>明文 下调、只读→可编辑、禁止导出→允许导出，以及把生效中的
 * 限制性规则（可见性非明文）的选择器或适用角色收窄。before 为 null = 变更前不生效（系统初始默认）。
 */
export function viewRuleSensitiveReasons(
  before: null | {
    allowExport: boolean;
    field: null | string;
    layer: ViewRuleLayer;
    maskRule?: null | string;
    moduleCode: null | string;
    operation: ViewRuleOperation;
    resource?: null | string;
    roleIds: number[];
    tenantId: null | number;
    visibility: ViewRuleVisibility;
  },
  after: null | ViewRuleVersionContent,
): string[] {
  const rank = (visibility: null | string): number => {
    switch (visibility) {
      case 'BLINDED': {
        return 3;
      }
      case 'HIDDEN': {
        return 4;
      }
      case 'MASKED': {
        return 2;
      }
      default: {
        return 1;
      }
    }
  };
  // null = 不生效 → 系统初始默认（明文、只读、禁止导出）
  const from = before ?? {
    allowExport: false,
    field: null,
    layer: 'GLOBAL' as ViewRuleLayer,
    moduleCode: null,
    operation: 'READONLY' as ViewRuleOperation,
    roleIds: [],
    tenantId: null,
    visibility: 'PLAIN' as ViewRuleVisibility,
  };
  const to = after ?? {
    allowExport: false,
    field: null,
    layer: 'GLOBAL' as ViewRuleLayer,
    maskRule: null,
    moduleCode: null,
    name: '',
    operation: 'READONLY' as ViewRuleOperation,
    resource: null,
    roleCodes: [],
    roleIds: [],
    status: 'DISABLED' as ViewRuleStatus,
    tenantId: null,
    visibility: 'PLAIN' as ViewRuleVisibility,
  };
  const reasons: string[] = [];
  if (rank(to.visibility) < rank(from.visibility)) {
    reasons.push('可见性放宽（改明文 / 取消设盲或隐藏）');
  }
  if (from.operation === 'READONLY' && to.operation === 'EDITABLE') {
    reasons.push('取消只读');
  }
  if (!from.allowExport && to.allowExport) {
    reasons.push('允许导出');
  }
  if (before && rank(before.visibility) > 1) {
    const selectorsChanged =
      before.layer !== to.layer ||
      before.tenantId !== to.tenantId ||
      before.moduleCode !== to.moduleCode ||
      (before.resource ?? null) !== to.resource ||
      before.field !== to.field;
    const rolesNarrowed =
      before.roleIds.length === 0
        ? to.roleIds.length > 0
        : to.roleIds.length > 0 &&
          !before.roleIds.every((id) => to.roleIds.includes(id));
    if (selectorsChanged) {
      reasons.push('限制性规则的作用范围变化');
    }
    if (rolesNarrowed) {
      reasons.push('限制性规则的适用角色收窄');
    }
  }
  return reasons;
}

// ---------------------------------------------------------------- 表单模型与校验

/** 编辑表单模型（对话框状态；resourceWhole 表示 FIELD 层选「整个资源对象」）。 */
export interface ViewRuleEditModel {
  allowExport: boolean;
  field: string;
  layer: ViewRuleLayer;
  maskRule: string;
  moduleCode: string;
  name: string;
  operation: ViewRuleOperation;
  resource: string;
  resourceWhole: boolean;
  roleIds: number[];
  status: ViewRuleStatus;
  tenantId: null | number;
  visibility: ViewRuleVisibility;
}

/** 新建默认模型（全局默认层、可见只读禁止导出——系统初始默认口径）。 */
export function newViewRuleModel(): ViewRuleEditModel {
  return {
    allowExport: false,
    field: '',
    layer: 'GLOBAL',
    maskRule: '',
    moduleCode: '',
    name: '',
    operation: 'READONLY',
    resource: '',
    resourceWhole: false,
    roleIds: [],
    status: 'ACTIVE',
    tenantId: null,
    visibility: 'PLAIN',
  };
}

/** 规则行 → 编辑模型。 */
export function toViewRuleEditModel(rule: ViewRuleItem): ViewRuleEditModel {
  return {
    allowExport: rule.allowExport,
    field: rule.field ?? '',
    layer: rule.layer,
    maskRule: rule.maskRule ?? '',
    moduleCode: rule.moduleCode ?? '',
    name: rule.name,
    operation: rule.operation,
    resource: rule.resource ?? '',
    resourceWhole: rule.layer === 'FIELD' && rule.field === null,
    roleIds: [],
    status: rule.status,
    tenantId: rule.tenantId,
    visibility: rule.visibility,
  };
}

/** 编辑模型 → 提交请求（不属当前层级的选择器一律置空，与后端形状校验同口径； FIELD 层保留可选模块——种子字段规则带模块限定，编辑不得静默丢失，E20-T05）。 */
export function toViewRuleSaveRequest(
  model: ViewRuleEditModel,
): ViewRuleSaveRequest {
  const isGlobal = model.layer === 'GLOBAL';
  const isModule = model.layer === 'MODULE';
  return {
    allowExport: model.allowExport,
    field:
      model.layer === 'FIELD' && !model.resourceWhole
        ? model.field || null
        : null,
    layer: model.layer,
    maskRule: model.visibility === 'MASKED' ? model.maskRule : null,
    moduleCode:
      isModule || model.layer === 'FIELD' ? model.moduleCode || null : null,
    name: model.name.trim(),
    operation: model.operation,
    resource: model.layer === 'FIELD' ? model.resource || null : null,
    roleIds: model.roleIds,
    status: model.status,
    tenantId: isGlobal ? null : (model.tenantId ?? null),
    visibility: model.visibility,
  };
}

/**
 * 保存校验（前端先行，与后端 ViewRuleService.validate 同口径；返回错误信息，空数组 = 通过）。
 * FIELD 层的资源与字段是否在目录内由页面用目录数据校验（见页面 logic.ts）。
 */
export function validateViewRule(model: ViewRuleEditModel): string[] {
  const errors: string[] = [];
  if (!model.name.trim()) {
    errors.push('请填写规则名称');
  } else if (model.name.trim().length > 128) {
    errors.push('规则名称过长（最多 128 个字符）');
  }
  switch (model.layer) {
    case 'FIELD': {
      if (!model.resource) errors.push('字段层级必须选择资源对象');
      if (!model.resourceWhole && !model.field) {
        errors.push('字段层级必须选择字段（或勾选「整个资源对象」）');
      }
      break;
    }
    case 'GLOBAL': {
      break;
    }
    case 'MODULE': {
      if (!model.moduleCode) errors.push('模块层级必须选择模块');
      break;
    }
    case 'TENANT': {
      if (!model.tenantId) errors.push('租户层级必须选择租户');
      break;
    }
    default: {
      errors.push('请选择层级');
    }
  }
  if (model.visibility === 'MASKED') {
    if (!model.maskRule) {
      errors.push('可见性为脱敏时必须选择脱敏规则');
    }
  } else if (model.maskRule) {
    errors.push('只有脱敏可见性才允许选择脱敏规则');
  }
  return errors;
}

// ---------------------------------------------------------------- 列表与预览文案

/** 规则的作用范围摘要（列表「范围」列）：租户 / 模块 / 字段选择器组合。 */
export function viewRuleScopeText(rule: ViewRuleItem): string {
  const parts: string[] = [
    rule.tenantId ? `${rule.tenantName ?? rule.tenantCode}` : '全部租户',
  ];
  if (rule.layer === 'MODULE' || rule.layer === 'FIELD') {
    parts.push(
      rule.moduleCode ? (rule.moduleName ?? rule.moduleCode) : '全部模块',
    );
  }
  if (rule.layer === 'FIELD') {
    parts.push(
      rule.field
        ? `${rule.resourceLabel ?? rule.resource} · ${rule.fieldLabel ?? rule.field}`
        : `${rule.resourceLabel ?? rule.resource}（整体）`,
    );
  }
  return parts.join(' / ');
}

/** 适用角色摘要（空 = 全部平台角色）。 */
export function viewRuleRolesText(rule: ViewRuleItem): string {
  return rule.roleCodes.length === 0 ? '全部角色' : rule.roleCodes.join('、');
}

/** 效果摘要（预览行内「可见性 · 操作 · 导出」一句话）。 */
export function viewRuleEffectText(effect: {
  allowExport: boolean;
  maskRule: null | string;
  operation: null | string;
  visibility: null | string;
}): string {
  const visibility =
    viewRuleVisibilityText(effect.visibility) +
    (effect.visibility === 'MASKED' && effect.maskRule
      ? `（${viewRuleMaskText(effect.maskRule)}）`
      : '');
  return `${visibility} · ${viewRuleOperationText(effect.operation)} · ${
    effect.allowExport ? '允许导出' : '禁止导出'
  }`;
}

/** 命中规则回显（Tooltip「由哪条规则决定」）。 */
export function viewRuleDecidedByText(traces: ViewRuleTrace[]): string {
  if (traces.length === 0) return '系统初始默认（无命中规则）';
  return traces.map((trace) => `${trace.code} ${trace.name}`).join('；');
}
