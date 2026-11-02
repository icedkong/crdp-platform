package com.crdp.framework.storage;

/**
 * 业务文件桶端口（E10-T02 / C-NFR-003「对象存储加密」；接口签名变更立案 ADR 0006）。
 *
 * <p>一个业务桶、每院一个前缀（tech-plan §2 修订）：调用方只给**相对键**（如 {@code files/202611/{uuid}.pdf}），实现按
 * TenantContext 强制加前缀——医院租户 {@code t/{租户编码}/}、 平台上下文 {@code
 * platform/}——从构造上保证按租户前缀隔离（任务验收），调用方无法寻址另一租户的对象。 前缀与校验规则见 {@link StorageKeys}。
 *
 * <p>实现：{@link ObsObjectStorage}（生产，OBS SSE-KMS 服务端加密）、{@link LocalObjectStorage}（本机 / CI 文件系统替身），按
 * {@code crdp.storage.type} 装配。与审计归档桶（{@link AuditArchiveStorage}，WORM）分离。
 */
public interface ObjectStorage {

    /**
     * 写入一个对象（相对键自动落在当前租户前缀下）。
     *
     * @param relativeKey 相对键（不含租户前缀；校验规则见 {@link StorageKeys}）
     * @param body 文件内容
     * @param contentType MIME 类型（随对象元数据保存）
     * @return 完整对象键（含租户前缀），入库引用用
     */
    String put(String relativeKey, byte[] body, String contentType);

    /**
     * 读回对象内容；对象不存在返回 {@code null}。
     *
     * @param relativeKey 相对键（仍按当前租户前缀解析，天然只读本院对象）
     */
    byte[] get(String relativeKey);

    /** 删除对象（幂等；对象不存在不视为错误）。软删除语义（台账状态）由调用方负责。 */
    void delete(String relativeKey);
}
