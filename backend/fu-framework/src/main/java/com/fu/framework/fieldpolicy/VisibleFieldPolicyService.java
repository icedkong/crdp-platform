package com.fu.framework.fieldpolicy;

import org.springframework.stereotype.Component;

@Component
public class VisibleFieldPolicyService implements FieldPolicyService {

    @Override
    public String decide(String resource, String field) {
        return "VISIBLE";
    }
}
