package com.crdp.platform.configtemplate;

/** 配置表导入方式（E12-T02 / A-FLD-014，与 {@link ConfigImportSpec} 配套）。 */
public enum ConfigImportMode {
    /** 按自然键 upsert：不存在插入（自增分配新 ID），存在则用包内值更新（源值胜，重放幂等）。 */
    COPY_ROWS,

    /** 纯关联表（无 id 列）：引用解析后 INSERT IGNORE，已存在的授权原样保留（并集）。 */
    LINK_ROWS,

    /** 单行表（固定主键，如 ntf_setting id=1）：带显式 id 的 upsert。 */
    SINGLE_ROW,

    /** 无自然键的子行表（fu_plan_node）：按父引用分组，整组替换（先删后插），防止重放重复。 */
    REPLACE_ROWS,

    /** 行全部丢弃：被引用对象不在复制清单（如 sys_role_scope_dept 引用 sys_dept），无法重映射。 */
    DROP_ROWS
}
