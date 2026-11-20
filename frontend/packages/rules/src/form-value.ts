/** 表单 JSON 值按内容比较；快照和当前值使用不同对象引用时也能正确判断脏状态。 */
export function sameFormValue(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    return (
      Array.isArray(left) &&
      Array.isArray(right) &&
      left.length === right.length &&
      left.every((value, index) => sameFormValue(value, right[index]))
    );
  }
  if (
    left !== null &&
    right !== null &&
    typeof left === 'object' &&
    typeof right === 'object'
  ) {
    const a = left as Record<string, unknown>;
    const b = right as Record<string, unknown>;
    const keys = Object.keys(a);
    return (
      keys.length === Object.keys(b).length &&
      keys.every(
        (key) => Object.hasOwn(b, key) && sameFormValue(a[key], b[key]),
      )
    );
  }
  return false;
}

/** 复制表单 JSON 值并剥离 Vue Proxy，供服务器请求和本机草稿冻结快照。 */
export function copyFormValue<T>(value: T): T {
  if (Array.isArray(value))
    return value.map((item) => copyFormValue(item)) as T;
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, copyFormValue(item)]),
    ) as T;
  }
  return value;
}
