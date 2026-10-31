/**
 * E04-T05 / PLT-AUTH-006：密码策略的前端实时提示（与后端 PasswordPolicy 同口径）。
 *
 * 平台下限：长度至少 8 位（租户可加严）、大写 / 小写 / 数字 / 特殊字符至少 3 类、不得包含账号。
 * 历史密码（最近 5 次）只能由后端比对哈希判定，前端提示里只说明规则。
 */

/** 平台下限（docs/requirements/platform-spec.md 第 7 章安全参数表）。 */
export const PASSWORD_MIN_LENGTH = 8;

/** 至少包含的字符类别数。 */
export const PASSWORD_MIN_CATEGORIES = 3;

/** 历史密码检查个数（后端比对，仅用于提示文案）。 */
export const PASSWORD_HISTORY_COUNT = 5;

/** 密码有效期（天），到期强制修改；到期前 7 天提醒。 */
export const PASSWORD_VALID_DAYS = 90;

/** 到期提醒窗口（天）。 */
export const PASSWORD_EXPIRY_REMIND_DAYS = 7;

/** 四类字符的命中数：大写 / 小写 / 数字 / 特殊字符（非字母数字视为特殊字符）。 */
export function passwordCategoryCount(password: null | string): number {
  if (!password) {
    return 0;
  }
  let upper = false;
  let lower = false;
  let digit = false;
  let special = false;
  for (const ch of password) {
    if (ch >= 'A' && ch <= 'Z') {
      upper = true;
    } else if (ch >= 'a' && ch <= 'z') {
      lower = true;
    } else if (ch >= '0' && ch <= '9') {
      digit = true;
    } else {
      special = true;
    }
  }
  return [upper, lower, digit, special].filter(Boolean).length;
}

export interface PasswordPolicyOptions {
  /** 最小长度（租户加严时传入更大值，默认平台下限 8）。 */
  minLength?: number;
}

/** 不满足的策略条目（文案与后端一致）；全部满足返回空数组。 */
export function passwordPolicyViolations(
  password: null | string,
  username: null | string,
  options: PasswordPolicyOptions = {},
): string[] {
  const minLength = options.minLength ?? PASSWORD_MIN_LENGTH;
  const violations: string[] = [];
  if (!password || password.length < minLength) {
    violations.push(`密码长度至少 ${minLength} 位`);
  }
  if (passwordCategoryCount(password) < PASSWORD_MIN_CATEGORIES) {
    violations.push(
      `密码需包含大写字母、小写字母、数字、特殊字符中的至少 ${PASSWORD_MIN_CATEGORIES} 类`,
    );
  }
  if (
    username &&
    password &&
    password.toLowerCase().includes(username.toLowerCase())
  ) {
    violations.push('密码不能包含账号');
  }
  return violations;
}

/** 改密页的规则清单（逐条打勾展示，满足 ✅ / 不满足 ⭕）。 */
export interface PasswordRuleItem {
  label: string;
  ok: boolean;
}

/** 按密码实时计算每条规则的满足情况（历史密码一条恒为提示，不参与打勾）。 */
export function passwordRuleItems(
  password: null | string,
  username: null | string,
  options: PasswordPolicyOptions = {},
): PasswordRuleItem[] {
  const minLength = options.minLength ?? PASSWORD_MIN_LENGTH;
  return [
    {
      label: `长度至少 ${minLength} 位`,
      ok: Boolean(password) && (password as string).length >= minLength,
    },
    {
      label: `包含大写 / 小写 / 数字 / 特殊字符中的 ${PASSWORD_MIN_CATEGORIES} 类`,
      ok: passwordCategoryCount(password) >= PASSWORD_MIN_CATEGORIES,
    },
    {
      label: '不包含账号',
      ok: Boolean(
        password &&
        username &&
        !password.toLowerCase().includes(username.toLowerCase()),
      ),
    },
  ];
}
