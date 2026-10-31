package com.fu.form.app;

import com.fu.form.api.FormEngine;
import org.springframework.stereotype.Component;

@Component
public class PlaceholderFormEngine implements FormEngine {

    @Override
    public String status() {
        return "placeholder";
    }
}
