/**
 * E10-T04：pdf.js 与 docx-preview 渲染（懒加载——只有真的预览 PDF / DOCX 才打包进对应入口； tech-plan §2 前端选型）。
 *
 * 内容一律来自预览代理短时链接（E10-T04 后端 `/api/hospital/files/preview/{token}`， 不缓存）；渲染失败时调用方展示下载兜底。
 * pdf.js worker 用 `?url` 由 Vite 产出独立资源地址（不引 CDN、不内联）。
 */

export async function renderPdfDocument(
  container: HTMLElement,
  url: string,
): Promise<number> {
  const [pdfjs, workerModule] = await Promise.all([
    import('pdfjs-dist'),
    import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
  ]);
  pdfjs.GlobalWorkerOptions.workerSrc = workerModule.default;
  const document_ = await pdfjs.getDocument({ url, withCredentials: false })
    .promise;
  try {
    container.replaceChildren();
    for (let pageNo = 1; pageNo <= document_.numPages; pageNo++) {
      const page = await document_.getPage(pageNo);
      const viewport = page.getViewport({ scale: 1.5 });
      const canvas = document.createElement('canvas');
      canvas.className = 'viewer-pdf-page';
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      const context = canvas.getContext('2d');
      if (!context) {
        throw new Error('canvas 2d context unavailable');
      }
      await page.render({ canvasContext: context, viewport }).promise;
      container.append(canvas);
    }
    return document_.numPages;
  } finally {
    await document_.destroy();
  }
}

export async function renderDocxDocument(
  container: HTMLElement,
  url: string,
): Promise<void> {
  const [{ renderAsync }, response] = await Promise.all([
    import('docx-preview'),
    fetch(url),
  ]);
  if (!response.ok) {
    throw new Error(`docx preview fetch failed: ${response.status}`);
  }
  const buffer = await response.arrayBuffer();
  container.replaceChildren();
  await renderAsync(buffer, container, undefined, {
    breakPages: true,
    inWrapper: true,
  });
}
