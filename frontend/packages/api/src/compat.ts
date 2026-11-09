/**
 * E24-T07 真机兼容（NFR-RESP-001/002/003 设备矩阵：iOS Safari、安卓 Chrome、
 * 微信内置浏览器）：微信 XWeb 与主流厂商浏览器内核可能落后于桌面 Chrome，
 * structuredClone（Chromium 98+）在旧内核上不存在，调用即 TypeError。
 * 站内需要深拷贝的载荷（高级检索条件树、任务筛选）都是**纯 JSON**（无 Date /
 * Map / 循环引用），JSON 往返即可等价替代，全内核可用。
 */

/**
 * 纯 JSON 值的深拷贝（structuredClone 的全内核替身）。
 *
 * 只用于纯 JSON 载荷：Date / Map / Set / undefined 属性 / 循环引用等与
 * JSON.stringify 口径不一致的值不在支持范围（调用点目前只有条件树与筛选对象）。
 */
export function deepCloneJson<T>(value: T): T {
  if (value === undefined) return value;
  // 分两步写：JSON 往返即本函数目的（structuredClone 的旧内核替身，见文件头）
  const json = JSON.stringify(value);
  return JSON.parse(json) as T;
}

/**
 * 自有属性判定（Object.hasOwn 的全内核替身，Chromium 93 之前不存在）。
 * `key in obj` 会连原型链一起算，不能替代。
 */
export function hasOwnProp(obj: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(obj, key);
}
