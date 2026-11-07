package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T01 验收测试（tasks.csv「两种版面都能解析到同一模型」）：A 规格 §4「示例：同一位虚构患者的两年随访」—— 同一患者同一份内容，宽表一行与长表两行解析出的 {@link
 * ImportPatientRecord} 语义相等（空值、空白与单元格坐标不参与 相等性；前置列是宽表专属溯源信息，同样不参与）。 tech-plan
 * §12「两种格式共用同一个导入引擎，只是版面适配器不同」。
 */
class BothLayoutsSameModelTest {

    /** 宽表 · 1 行：病案号 X0001，第 1 / 2 年随访（规格示例原文，日期写作医院宽表的 yyyymmdd）。 */
    private static ImportParseResult parseWide() {
        List<String> header = LayoutFixture.wideHeader(2);
        List<String> row =
                LayoutFixture.row(
                        header.size(),
                        "1",
                        "L0001", // 患者编号 → 原系统编号
                        "X0001", // 前置列病案号
                        "张三（示例）",
                        "男",
                        "65",
                        "20260110",
                        "冠心病",
                        "90%",
                        "20260115", // 基线：手术日期
                        "X0001", // 基线：病案号（以基线列为准）
                        "张三（示例）", // 基线：姓名
                        "1", // 基线：性别
                        "65", // 基线：年龄
                        "20260318", // 第 1 年：随访日期
                        "0", // 死亡
                        "N/A", // 死亡随访时间
                        "45", // EF
                        "20270325", // 第 2 年：随访日期
                        "0",
                        "",
                        "47");
        return new WideLayoutAdapter()
                .parse(LayoutFixture.wideWorkbook(header, List.of(row)), LayoutFixture.catalog());
    }

    /** 长表 · 基线 1 行 + 随访记录 2 行：同一患者的同一份内容。 */
    private static ImportParseResult parseLong() {
        return new LongLayoutAdapter()
                .parse(
                        LayoutFixture.longWorkbook(
                                List.of(
                                        LayoutFixture.LONG_BASELINE_HEADERS,
                                        LayoutFixture.row(
                                                LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                                "20260115",
                                                "X0001",
                                                "张三（示例）",
                                                "1",
                                                "",
                                                "65",
                                                "L0001")),
                                List.of(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                        LayoutFixture.row(
                                                LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                                "X0001",
                                                "1",
                                                "20260318",
                                                "0",
                                                "45"),
                                        LayoutFixture.row(
                                                LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                                "X0001",
                                                "2",
                                                "20270325",
                                                "0",
                                                "47"))),
                        LayoutFixture.catalog());
    }

    @Test
    void bothLayoutsParseToTheSameModel() {
        ImportParseResult wide = parseWide();
        ImportParseResult longResult = parseLong();

        assertEquals(ImportLayout.WIDE, wide.layout());
        assertEquals(ImportLayout.LONG, longResult.layout());
        assertEquals(1, wide.patients().size());
        assertEquals(1, longResult.patients().size());
        assertEquals(wide.patients().get(0), longResult.patients().get(0), "同一内容的宽表与长表应解析到同一模型");
        assertEquals(wide.unmatchedFollowups(), longResult.unmatchedFollowups());
    }

    /** 同一入口（版面识别 + 分发）也能得到相同结果：上传侧无需先告诉系统是哪种版面。 */
    @Test
    void parserFacadeDispatchesBothLayoutsToSameModel() {
        ImportParseResult wide =
                ImportWorkbookParser.parse(wideWorkbook(), LayoutFixture.catalog());
        ImportParseResult longResult =
                ImportWorkbookParser.parse(longWorkbook(), LayoutFixture.catalog());

        assertEquals(ImportLayout.WIDE, wide.layout());
        assertEquals(ImportLayout.LONG, longResult.layout());
        assertEquals(wide.patients().get(0), longResult.patients().get(0));
    }

    private static ImportWorkbookData wideWorkbook() {
        List<String> header = LayoutFixture.wideHeader(2);
        return LayoutFixture.wideWorkbook(
                header,
                List.of(
                        LayoutFixture.row(
                                header.size(),
                                "1",
                                "L0001",
                                "X0001",
                                "张三（示例）",
                                "男",
                                "65",
                                "20260110",
                                "冠心病",
                                "90%",
                                "20260115",
                                "X0001",
                                "张三（示例）",
                                "1",
                                "65",
                                "20260318",
                                "0",
                                "N/A",
                                "45",
                                "20270325",
                                "0",
                                "",
                                "47")));
    }

    private static ImportWorkbookData longWorkbook() {
        return LayoutFixture.longWorkbook(
                List.of(
                        LayoutFixture.LONG_BASELINE_HEADERS,
                        LayoutFixture.row(
                                LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                "20260115",
                                "X0001",
                                "张三（示例）",
                                "1",
                                "",
                                "65",
                                "L0001")),
                List.of(
                        LayoutFixture.LONG_FOLLOWUP_HEADERS,
                        LayoutFixture.row(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                "X0001",
                                "1",
                                "20260318",
                                "0",
                                "45"),
                        LayoutFixture.row(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                "X0001",
                                "2",
                                "20270325",
                                "0",
                                "47")));
    }
}
