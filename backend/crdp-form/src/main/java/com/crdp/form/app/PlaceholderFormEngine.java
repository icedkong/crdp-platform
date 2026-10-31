package com.crdp.form.app;

import com.crdp.form.api.FormEngine;
import org.springframework.stereotype.Component;

@Component
public class PlaceholderFormEngine implements FormEngine {

    @Override
    public String status() {
        return "placeholder";
    }
}
