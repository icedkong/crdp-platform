package com.crdp.server.followup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医院 A 规格「附录 A. 字段字典全表（266 个字段）」解析（E23-T01 核对用）。
 *
 * <p>直接读仓库 docs/requirements/hospital-a-spec.md 的附录 A 表格（序号 / 显示名 · 编码 / 类型 / 取值与说明）， 供 {@link
 * AFieldDictSqlTest}（迁移 SQL ↔ 附录逐项核对）与 AFieldDictVerifyIT（真实库 ↔ 附录核对）共用同一口径； 附录本身即验收基准（tasks.csv
 * E23-T01「266 个字段与附录 A 一致」）。
 *
 * <p>取值与说明里的两类已确认笔误按规格 §3「业务规则」修正后比对：术前最后一次肌酐单位按 μmol/L（编码保留原列名）； 「主动动脉瓣」选项显示名按「主动脉瓣」。
 */
public final class AFieldDictAppendix {

    /** 附录行：序号、显示名、字段编码（库列名）、附录类型、取值与说明原文。 */
    public record Row(int no, String label, String code, String appendixType, String spec) {

        /** 基础必填（附录「必填」；「死亡时必填 / 前项…时必填」为条件必填，不算）。 */
        public boolean required() {
            return spec.contains("必填") && !spec.contains("时必填");
        }

        /** 单位（附录「单位 X；」；括注（建议）（原表 mmol/L）等剥离）。 */
        public String unit() {
            Matcher matcher = UNIT.matcher(spec);
            if (!matcher.find()) {
                return null;
            }
            return clean(matcher.group(1).trim());
        }

        /** 范围（附录「范围 X–Y」；数值 / 整数 / 派生字段才有）。 */
        public double[] range() {
            Matcher matcher = RANGE.matcher(spec());
            if (!matcher.find()) {
                return null;
            }
            return new double[] {
                Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))
            };
        }

        /**
         * 选项（编码 + 显示名）：「0=否 / 1=是」式取编码；「取值：无 / 微量 / …」按列出顺序编 0..n-1； 「建议：经胸 / 经食道 / 床旁」同样按顺序（超声类型由
         * E23-T01 定编码 1..n，见生成器）； 引用字典（患者类型 / 术者 / 病区）无固定选项，返回空。
         */
        public Map<Integer, String> options() {
            return parseOptions(spec());
        }
    }

    private static final Pattern ROW =
            Pattern.compile(
                    "^\\|\\s*(\\d+)\\s*\\|\\s*(.+?)\\s*·\\s*`([^`]+)`\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]*?)\\s*\\|$");
    private static final Pattern UNIT = Pattern.compile("单位 ([^；]+)");
    private static final Pattern RANGE = Pattern.compile("范围 ([0-9.]+)–([0-9.]+)");
    private static final Pattern CODED_OPTION = Pattern.compile("(\\d+)=([^/]+)");

    /** 附录 A 全部 266 行（按序号）。文档缺失时直接失败（核对基准不可少）。 */
    public static List<Row> rows() {
        Path spec = locateSpec();
        List<String> lines;
        try {
            lines = Files.readAllLines(spec, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读医院 A 规格失败：" + spec, e);
        }
        List<Row> rows = new ArrayList<>();
        boolean inAppendix = false;
        for (String line : lines) {
            if (line.startsWith("## 附录 A.")) {
                inAppendix = true;
                continue;
            }
            if (inAppendix && line.startsWith("## ")) {
                break;
            }
            if (!inAppendix || !line.startsWith("|")) {
                continue;
            }
            Matcher matcher = ROW.matcher(line);
            if (matcher.matches()) {
                rows.add(
                        new Row(
                                Integer.parseInt(matcher.group(1)),
                                matcher.group(2).trim(),
                                matcher.group(3).trim(),
                                matcher.group(4).trim(),
                                matcher.group(5).trim()));
            }
        }
        if (rows.size() != 266) {
            throw new IllegalStateException("附录 A 应解析出 266 行，实际 " + rows.size() + "（" + spec + "）");
        }
        return rows;
    }

    /** 从「从模块目录向上找到仓库根」（Maven 与 IDE 的工作目录不一致都能命中）。 */
    public static Path locateSpec() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs/requirements/hospital-a-spec.md");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 docs/requirements/hospital-a-spec.md（应在仓库内运行）");
    }

    private static Map<Integer, String> parseOptions(String spec) {
        String head = spec.split("；", 2)[0].trim();
        // 引用字典（租户可配置）：无固定选项
        if (head.contains("字典（可配置")) {
            return Map.of();
        }
        String list = head;
        if (head.startsWith("取值：") || head.startsWith("建议：")) {
            list = head.substring(3).trim();
            Map<Integer, String> labels = new LinkedHashMap<>();
            int code = head.startsWith("建议：") ? 1 : 0;
            for (String part : list.split("\\s*/\\s*")) {
                labels.put(code++, clean(part));
            }
            return labels;
        }
        Map<Integer, String> coded = new LinkedHashMap<>();
        Matcher matcher = CODED_OPTION.matcher(head);
        while (matcher.find()) {
            coded.put(Integer.parseInt(matcher.group(1)), clean(matcher.group(2)));
        }
        return coded;
    }

    /** 剥离括注（（建议）（表中未写编码，建议）等）并套用已确认笔误修正（主动动脉瓣 → 主动脉瓣）。 */
    private static String clean(String raw) {
        String value = raw.trim();
        int paren = value.indexOf('（');
        if (paren > 0) {
            value = value.substring(0, paren).trim();
        }
        return "主动动脉瓣".equals(value) ? "主动脉瓣" : value;
    }

    private AFieldDictAppendix() {}
}
