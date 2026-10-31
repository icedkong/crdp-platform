package com.crdp.framework.excel;

import org.springframework.stereotype.Component;

@Component
public class UnsupportedExcelGateway implements ExcelGateway {

    @Override
    public void assertSupported() {
        throw new UnsupportedOperationException("Excel 流式读写将在 E18 实现");
    }
}
