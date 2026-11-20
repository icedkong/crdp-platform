/**
 * 服务器水印 PDF 的懒加载渲染器。输入为鉴权获取的临时内存地址，DOCX 预览也已由服务器转换为 PDF。
 * pdf.js worker 由 Vite 打包，不使用外部 CDN；失败交由调用方显示提示，下载仍需独立权限。
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

/** DOCX 的服务端预览已转换为水印 PDF；不在浏览器解析原始 DOCX。 */
export async function renderDocxDocument(
  container: HTMLElement,
  url: string,
): Promise<void> {
  await renderPdfDocument(container, url);
}
