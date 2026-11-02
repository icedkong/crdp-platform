package com.crdp.followup.file.domain;

/** 文件类别：大小上限按类别区分（A-ATT-004：文档图片 20 MB、录音 50 MB）。 */
public enum FileCategory {
    DOCUMENT("文档与图片"),
    AUDIO("录音");

    private final String label;

    FileCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
