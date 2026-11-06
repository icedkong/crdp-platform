package com.crdp.server.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** E12-T01 / A-FLD-014：配置包模型。值归一化（数据库类型 → 可再导入的字符串 / 基本类型）与 JSON 往返。 */
class ConfigPackageTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void normalizesDatabaseCellValues() {
        LocalDateTime dateTime = LocalDateTime.of(2027, 4, 19, 9, 30, 15);
        assertThat(ConfigPackage.cellValue(dateTime)).isEqualTo("2027-04-19 09:30:15");
        assertThat(ConfigPackage.cellValue(LocalDateTime.of(2027, 4, 19, 9, 30, 15, 250_000_000)))
                .isEqualTo("2027-04-19 09:30:15.250");
        assertThat(ConfigPackage.cellValue(LocalDate.of(2027, 4, 19))).isEqualTo("2027-04-19");
        assertThat(ConfigPackage.cellValue(java.sql.Timestamp.valueOf("2027-04-19 09:30:15")))
                .isEqualTo("2027-04-19 09:30:15");
        assertThat(ConfigPackage.cellValue(java.sql.Date.valueOf("2027-04-19")))
                .isEqualTo("2027-04-19");
        assertThat(ConfigPackage.cellValue(new byte[] {1, 2, -1}))
                .isEqualTo(Base64.getEncoder().encodeToString(new byte[] {1, 2, -1}));
        assertThat(ConfigPackage.cellValue(null)).isNull();
        assertThat(ConfigPackage.cellValue(42)).isEqualTo(42);
        assertThat(ConfigPackage.cellValue("文本")).isEqualTo("文本");
        assertThat(ConfigPackage.cellValue(Boolean.TRUE)).isEqualTo(true);
    }

    @Test
    void serializesAndReadsBack() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("code", "smoking_history");
        row.put("created_at", "2027-04-19 09:30:15");
        ConfigPackage.TableSection field =
                new ConfigPackage.TableSection(
                        "fe_field",
                        "followup",
                        "COPY",
                        "字段字典与表单",
                        true,
                        1,
                        0,
                        1,
                        List.of("id", "code", "created_at"),
                        List.of(row));
        ConfigPackage.TableSection ward =
                new ConfigPackage.TableSection(
                        "ward",
                        "followup",
                        "STRUCTURE_ONLY",
                        "病区 / 中心（只复制结构）",
                        true,
                        0,
                        0,
                        12,
                        List.of("id", "code", "name"),
                        List.of());
        ConfigPackage pkg =
                new ConfigPackage(
                        1, "demoa", "医院 A（示例）", "2027-04-19T09:30:15+08:00", List.of(field, ward));

        String text = json.writeValueAsString(pkg);
        ConfigPackage back = json.readValue(text, ConfigPackage.class);

        assertThat(back.tenantCode()).isEqualTo("demoa");
        assertThat(back.tables()).hasSize(2);
        // JSON 往返后小整数变 Integer（导入侧按 Number 读，无影响）
        assertThat(back.tables().get(0).rows())
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "id",
                                        7,
                                        "code",
                                        "smoking_history",
                                        "created_at",
                                        "2027-04-19 09:30:15")));
        assertThat(back.tables().get(1).mode()).isEqualTo("STRUCTURE_ONLY");
        assertThat(back.tables().get(1).sourceRowCount()).isEqualTo(12);
        assertThat(back.tables().get(1).rows()).isEmpty();
        // 包里只有配置表节，不携带患者表
        assertThat(text).doesNotContain("pt_patient");
    }
}
