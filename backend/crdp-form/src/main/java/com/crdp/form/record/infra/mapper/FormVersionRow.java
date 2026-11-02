package com.crdp.form.record.infra.mapper;

import java.time.LocalDateTime;

/**
 * fe_form_version 行（E11-T02 / A-NFR-002、C-NFR-002）：一次 publish 固化的模板版本快照。
 *
 * <p>发布后不可变（只有新版本行，没有修改）；{@code categories} 为 JSON 文本，应用层解析。
 */
public record FormVersionRow(
        Long id,
        String formCode,
        int version,
        String categories,
        int fieldCount,
        Long publishedBy,
        LocalDateTime publishedAt) {}
