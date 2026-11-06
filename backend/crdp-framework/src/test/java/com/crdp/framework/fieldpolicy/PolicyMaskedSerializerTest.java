package com.crdp.framework.fieldpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E07-T05：Jackson 序列化器读取当前请求的字段策略（tech-plan §5「实现」行）。 DTO 字段标 {@link MaskedByPolicy}，按会话快照输出 明文 /
 * 掩码 / null（隐藏），无会话（作业、未登录旁路）fail closed 脱敏。 列表、详情、导出、站内信共用同一套注解语义。
 */
class PolicyMaskedSerializerTest {

    /** 与 E13 起的患者 DTO 同构的最小样例（字段全部虚构）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SampleRow(
            @MaskedByPolicy(resource = "patient", field = "name") String name,
            @MaskedByPolicy(resource = "patient", field = "phone") String phone,
            @MaskedByPolicy(resource = "patient", field = "address") String address,
            String gender) {}

    /** 与服务端一致：主 ObjectMapper 注册 FieldPolicyJacksonModule（Boot 自动并入 Module Bean）。 */
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new FieldPolicyJacksonModule());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginWith(FieldPolicySnapshot policies) {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token",
                        "HOSPITAL",
                        1L,
                        "demoa",
                        "虚构示例医院",
                        9L,
                        "itr.demo",
                        "IT 用户（示例）",
                        List.of("ITR-ROLE"),
                        List.of(),
                        List.of(),
                        List.of("followup"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null,
                        null,
                        0L,
                        0L,
                        0L,
                        0L,
                        policies);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void maskedPolicyWritesMaskedValue() throws Exception {
        loginWith(FieldPolicySnapshot.defaults());

        String json =
                mapper.writeValueAsString(
                        new SampleRow("张小明", "13800005678", "北京市东城区某某街道某小区", "男"));

        assertEquals(
                "{\"name\":\"张*明\",\"phone\":\"138****5678\",\"address\":\"北京市东城区****\",\"gender\":\"男\"}",
                json);
    }

    @Test
    void visiblePolicyWritesPlainValue() throws Exception {
        loginWith(
                FieldPolicySnapshot.merge(
                        List.of(new FieldPolicyOverride("patient", "name", "VISIBLE"))));

        String json = mapper.writeValueAsString(new SampleRow("张小明", "13800005678", null, "男"));

        assertEquals("{\"name\":\"张小明\",\"phone\":\"138****5678\",\"gender\":\"男\"}", json);
    }

    @Test
    void hiddenPolicyWritesNullAndNonNullable() throws Exception {
        loginWith(
                FieldPolicySnapshot.merge(
                        List.of(
                                new FieldPolicyOverride("patient", "name", "HIDDEN"),
                                new FieldPolicyOverride("patient", "address", "HIDDEN"))));

        String json =
                mapper.writeValueAsString(
                        new SampleRow("张小明", "13800005678", "北京市东城区某某街道某小区", "男"));

        assertEquals(
                "{\"phone\":\"138****5678\",\"gender\":\"男\"}",
                json,
                "隐藏字段输出 null 并被 NON_NULL 剔除（DTO 需配 @JsonInclude(NON_NULL)）");
    }

    @Test
    void withoutSessionFailsClosedToMasked() throws Exception {
        String json = mapper.writeValueAsString(new SampleRow("张小明", "13800005678", null, "男"));

        assertEquals(
                "{\"name\":\"张*明\",\"phone\":\"138****5678\",\"gender\":\"男\"}",
                json,
                "无会话（定时作业、异步旁路）一律脱敏，绝不漏明文");
    }

    @Test
    void nullValueStaysNullWithoutMasking() throws Exception {
        loginWith(FieldPolicySnapshot.defaults());

        String json = mapper.writeValueAsString(new SampleRow(null, null, null, null));

        assertEquals("{}", json);
    }

    @Test
    void worksInsideMapsAndNestedStructures() throws Exception {
        loginWith(FieldPolicySnapshot.defaults());

        String json =
                mapper.writeValueAsString(
                        Map.of("items", List.of(new SampleRow("张小明", "13800005678", null, "男"))));

        assertEquals(
                "{\"items\":[{\"name\":\"张*明\",\"phone\":\"138****5678\",\"gender\":\"男\"}]}",
                json);
    }
}
