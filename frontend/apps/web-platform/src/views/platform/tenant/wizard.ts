/**
 * 新建租户向导的纯逻辑（E02-T07 / PLT-TEN-002）：编码建议值、编码与口令校验、开通步骤展示口径。页面只做展示与编排。
 */

const CODE_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';

/** 开通编排步骤序号 → 展示标题（与后端 TenantOnboardingService 的 step 对应）。 */
export const PROVISION_TITLES: Record<string, string> = {
  admin: '账号初始化（首个管理员）',
  'config-template': '配置模板初始化',
  database: '建库与账号',
  'migrate-register': '初始化与注册',
  'resource-row': '资源登记',
  'tenant-row': '租户登记',
};

/**
 * 开通步骤 SKIPPED 的展示文案（结果页进度条）：admin 是「启用前补设」， config-template 是「未选择模板」（E12-T05），
 * 其余步骤不存在跳过态（后端只会 SUCCESS / FAILED）。
 */
export const PROVISION_SKIP_TEXTS: Record<string, string> = {
  admin: '已跳过，启用前补设',
  'config-template': '未选择配置模板，已跳过',
};

/** 步骤 SKIPPED 文案（未知步骤兜底为通用文案）。 */
export function provisionSkipText(step: string): string {
  return PROVISION_SKIP_TEXTS[step] ?? '已跳过';
}

/** 租户编码规则：小写字母开头，小写字母、数字、下划线（平台规格"租户编码"）。 */
export const TENANT_CODE_PATTERN = /^[a-z][a-z0-9_]{0,31}$/;

/** 编码建议值：页面可改。 */
export function suggestTenantCode(): string {
  const array = [...crypto.getRandomValues(new Uint32Array(7))];
  const chars = array.map(
    (value) => CODE_ALPHABET[value % CODE_ALPHABET.length],
  );
  return `t${chars.join('')}`;
}

export function validateTenantCode(code: string): string | undefined {
  if (!TENANT_CODE_PATTERN.test(code)) {
    return '编码须以小写字母开头，仅含小写字母、数字、下划线，最长 32 位';
  }
  return undefined;
}

export function validateInitialPassword(password: string): string | undefined {
  if (password.length < 8 || password.length > 64) {
    return '初始口令长度为 8 至 64 位';
  }
  return undefined;
}

/** 访问地址预览（PLT-TEN-015）：租户子域名 {编码}.平台域名；统一登录页可用编码登录。 */
export function subdomainPreview(code: string): string {
  return `${code}.<平台域名>`;
}

/**
 * 配置模板选项文案（E12-T05 向导第 2 步）：名称 + 源租户与版本， 控制在 32 字内避免下拉溢出。
 */
export function templateOptionLabel(template: {
  name: string;
  tenantName: string;
  version: number;
}): string {
  return `${template.name}（源：${template.tenantName} v${template.version}）`;
}
