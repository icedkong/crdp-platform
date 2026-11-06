package com.crdp.platform.configtemplate;

/** 配置表在配置模板里的复制方式（A-FLD-014「复制 / 只复制结构」两档；「不复制」= 不登记）。 */
public enum ConfigCopyMode {

    /** 全量复制行，导入时重新分配 ID 并重写引用（E12-T02）。 */
    COPY,

    /** 只复制表定义（登记口径），不带取值——术者、病区 / 中心字典（A-FLD-014）。 */
    STRUCTURE_ONLY
}
