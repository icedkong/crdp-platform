import type { FilePreviewGrant } from '@crdp/api';

import { getCurrentScope, onBeforeUnmount, onScopeDispose, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { PREVIEW_REVOKED_EVENT } from '@crdp/viewer';

const active = new Map<string, () => void>();

/** 只释放本模块创建的内存副本，绝不将原件地址当作可读预览。 */
export function releasePreviewUrls(urls: Iterable<string>) {
  for (const url of urls) active.get(url)?.();
}

export async function previewBlob(
  grant: FilePreviewGrant,
  load: (url: string) => Promise<Blob>,
): Promise<FilePreviewGrant> {
  const access = useAccessStore();
  const user = useUserStore();
  let disposed = false;
  let url = '';
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stop = () => {};
  const release = () => {
    disposed = true;
    stop();
    if (timer) clearTimeout(timer);
    if (url) {
      URL.revokeObjectURL(url);
      active.delete(url);
      window.dispatchEvent(
        new CustomEvent(PREVIEW_REVOKED_EVENT, { detail: url }),
      );
      url = '';
    }
  };
  stop = watch(
    () => [access.accessToken, user.userInfo?.permissionVersion],
    release,
    { flush: 'sync' },
  );
  if (getCurrentScope()) onScopeDispose(release);
  try {
    const blob = await load(grant.url);
    if (disposed) throw new Error('预览会话已变化，请重新打开');
    const remaining = grant.expiresAtEpochSecond * 1000 - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0)
      throw new Error('预览已过期，请重新打开');
    url = URL.createObjectURL(blob);
    active.set(url, release);
    timer = setTimeout(release, Math.min(remaining, 300_000));
    return { ...grant, url };
  } catch (error) {
    release();
    throw error;
  }
}

/** 缩略图与查看器一起撤销已解码内容；已交付的内存数据也不跨账号 / 权限版本展示。 */
export function trackPreviewUrls(
  urls: import('vue').Ref<Record<string, string>>,
  invalidated?: () => void,
) {
  track(
    () => Object.values(urls.value),
    (revoked) => {
      if (!Object.values(urls.value).includes(revoked)) return;
      urls.value = Object.fromEntries(
        Object.entries(urls.value).filter(([, value]) => value !== revoked),
      );
      invalidated?.();
    },
  );
}
export function trackPreviewUrl(
  url: import('vue').Ref<null | string> | import('vue').Ref<string>,
  invalidated?: () => void,
) {
  track(
    () => (url.value ? [url.value] : []),
    (revoked) => {
      if (url.value !== revoked) return;
      url.value = '';
      invalidated?.();
    },
  );
}
function track(current: () => string[], invalidate: (url: string) => void) {
  const listener = (event: Event) =>
    invalidate((event as CustomEvent<string>).detail);
  window.addEventListener(PREVIEW_REVOKED_EVENT, listener);
  onBeforeUnmount(() => {
    window.removeEventListener(PREVIEW_REVOKED_EVENT, listener);
    releasePreviewUrls(current());
  });
}
