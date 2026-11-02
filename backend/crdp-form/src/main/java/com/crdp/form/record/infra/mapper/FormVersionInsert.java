package com.crdp.form.record.infra.mapper;

/**
 * fe_form_version 插入行（E11-T02）。可变行对象（{@code useGeneratedKeys} 回填 id，仓库既有口径： OrgRow / SysFileRow
 * 同款）。
 */
public class FormVersionInsert {

    private Long id;
    private String formCode;
    private int version;
    private String categories;
    private int fieldCount;
    private Long publishedBy;

    public FormVersionInsert() {}

    public FormVersionInsert(
            String formCode, int version, String categories, int fieldCount, Long publishedBy) {
        this.formCode = formCode;
        this.version = version;
        this.categories = categories;
        this.fieldCount = fieldCount;
        this.publishedBy = publishedBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFormCode() {
        return formCode;
    }

    public void setFormCode(String formCode) {
        this.formCode = formCode;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getCategories() {
        return categories;
    }

    public void setCategories(String categories) {
        this.categories = categories;
    }

    public int getFieldCount() {
        return fieldCount;
    }

    public void setFieldCount(int fieldCount) {
        this.fieldCount = fieldCount;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }
}
