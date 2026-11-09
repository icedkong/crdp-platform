/**
 * E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006：手机扫码上传 H5 的接口客户端。
 *
 * 后端契约见 docs/api/report.md §5：`/api/h5/upload/{token}`（信息）、`/{token}/context`
 * （引导）、`/{token}/files`（上传，multipart）、`/{token}/submit`（提交）；统一响应
 * `{ code, message, data }`。C 租户打开 `h5_upload_login` 后须先登录本院账号
 * （C-NFR-006）：会话令牌存 sessionStorage（标签页关闭即清；不落 localStorage /
 * IndexedDB，AGENTS §5.3），请求经 Authorization 头携带。
 */

/** 带 code/message 的业务错误（40101 = 二维码过期 / 已用；40100 = 需先登录）。 */
export class H5ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

const SESSION_KEY = 'crdp.h5.session';

export function sessionToken(): null | string {
  try {
    return globalThis.sessionStorage?.getItem(SESSION_KEY) ?? null;
  } catch {
    return null;
  }
}

export function saveSessionToken(token: string) {
  try {
    globalThis.sessionStorage?.setItem(SESSION_KEY, token);
  } catch {
    // 隐私模式等存储不可用：登录态只留内存（本次页面生命周期内有效）
  }
}

export function clearSessionToken() {
  try {
    globalThis.sessionStorage?.removeItem(SESSION_KEY);
  } catch {
    // 忽略
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {};
  const session = sessionToken();
  if (session) {
    headers.Authorization = `Bearer ${session}`;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = (await response.json()) as {
    code: number;
    data: T;
    message: string;
  };
  if (payload.code !== 0) {
    throw new H5ApiError(payload.code, payload.message);
  }
  return payload.data;
}

/** 引导信息（无需登录）：租户预填 + 是否要求手机端登录 + 到期时间。 */
export interface H5Context {
  tenantCode: string;
  tenantName: string;
  requireLogin: boolean;
  expiresAtEpochSecond: number;
}

export function fetchContext(token: string) {
  return request<H5Context>('GET', `/api/h5/upload/${token}/context`);
}

export interface H5NameOption {
  id: string;
  name: string;
}

export interface H5TypeOption {
  code: string;
  name: string;
  nameRequired: boolean;
  names: H5NameOption[];
}

export interface H5LocationValue {
  linkType: string;
  linkRecordId: null | string;
  anchorType: null | string;
  anchorKey: null | string;
  anchorTemplateVersion: number;
}

export interface H5LocationOption {
  key: string;
  label: string;
  value: H5LocationValue;
}

export interface H5Info {
  patientName: string;
  medicalRecordNo: string;
  wardName: null | string;
  today: string;
  types: H5TypeOption[];
  locationOptions: H5LocationOption[];
  presetKey: null | string;
  requireLogin: boolean;
  expiresAtEpochSecond: number;
}

export function fetchInfo(token: string) {
  return request<H5Info>('GET', `/api/h5/upload/${token}`);
}

export interface H5UploadedFile {
  fileId: string;
  sha256: string;
  size: number;
  mime: string;
  format: string;
  originalName: string;
}

/** 上传一张图片（multipart；onProgress 0–100）。走 XHR 以获得上传进度。 */
export function uploadPhoto(
  token: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<H5UploadedFile> {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('file', file, file.name);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/h5/upload/${token}/files`);
    const session = sessionToken();
    if (session) {
      xhr.setRequestHeader('Authorization', `Bearer ${session}`);
    }
    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    });
    xhr.addEventListener('load', () => {
      try {
        const payload = JSON.parse(xhr.responseText) as {
          code: number;
          data: H5UploadedFile;
          message: string;
        };
        if (payload.code === 0) {
          resolve(payload.data);
        } else {
          reject(new H5ApiError(payload.code, payload.message));
        }
      } catch {
        reject(new H5ApiError(50_000, '上传失败，请重试'));
      }
    });
    // XHR 网络级失败（断网 / DNS）：与 HTTP 错误码不同路径，同样收敛为可重试错误
    xhr.addEventListener('error', () =>
      reject(new H5ApiError(50_000, '网络异常，请重试')),
    );
    xhr.send(form);
  });
}

export interface H5SubmitPayload {
  typeCode: string;
  nameId: null | string;
  reportDate: string;
  locations: H5LocationValue[];
}

export interface H5CreatedReport {
  id: string;
  typeCode: string;
  typeName: string;
  name: null | string;
  reportDate: string;
  locationCount: number;
  fileCount: number;
}

export function submitUpload(token: string, payload: H5SubmitPayload) {
  return request<H5CreatedReport>(
    'POST',
    `/api/h5/upload/${token}/submit`,
    payload,
  );
}

/** C 租户手机端登录（/api/hospital/auth/login；租户编码由令牌引导信息预填）。 */
export function hospitalLogin(
  tenantCode: string,
  username: string,
  password: string,
): Promise<{ expiresInSeconds: number; token: string }> {
  return request('POST', '/api/hospital/auth/login', {
    tenantCode,
    username,
    password,
  });
}
