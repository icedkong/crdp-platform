package com.fu.server.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fu.cors")
public class CorsProperties {

    private List<String> allowedOriginPatterns =
            new ArrayList<>(List.of("http://127.0.0.1:*", "http://localhost:*"));

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }
}
