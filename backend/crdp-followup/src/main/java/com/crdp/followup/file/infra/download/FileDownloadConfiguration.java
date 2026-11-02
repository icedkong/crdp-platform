package com.crdp.followup.file.infra.download;

import com.crdp.followup.file.app.FileDownloadProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 签名下载装配（E10-T05）：签名器不是简单组件（密钥来自配置，非法即启动失败 fail-fast）， 经此处集中构造，方便在测试里直接 new。 */
@Configuration
public class FileDownloadConfiguration {

    @Bean
    public FileDownloadSigner fileDownloadSigner(FileDownloadProperties properties) {
        return new FileDownloadSigner(properties.getSigningKey());
    }
}
