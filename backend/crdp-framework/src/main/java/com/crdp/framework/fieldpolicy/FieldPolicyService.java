package com.crdp.framework.fieldpolicy;

/** 字段策略。M0 一律可见，合并规则与脱敏序列化在 E07。 */
public interface FieldPolicyService {

    String decide(String resource, String field);
}
