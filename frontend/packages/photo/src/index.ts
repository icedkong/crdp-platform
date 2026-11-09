/**
 * E16-T10 / OQ-116⑤：拍照上传共享链路。image-compress 原在 web-hospital
 * （E24-T06），手机扫码上传 H5（apps/mobile-upload）需要同一压缩口径
 * （长边 ≤ 2560、转 JPEG 含 HEIC、画布重编码去 EXIF），上提为共享包；
 * web-hospital 添加报告单对话框与本包同一实现。
 */
export * from './image-compress';
