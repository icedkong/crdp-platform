package com.fu.framework.datascope;

import java.util.Set;
import java.util.regex.Pattern;

/** 患者及其子表。未在此登记的表（用户、菜单、字典）不拦截。 */
public final class ControlledTables {

    public static final Set<String> NAMES =
            Set.of(
                    "pt_patient",
                    "pt_baseline_section",
                    "pt_baseline_lock",
                    "pt_contact",
                    "pt_status_history",
                    "pt_ward_history",
                    "pt_patient_summary",
                    "pt_implant",
                    "pt_lead",
                    "pt_implant_lead",
                    "fu_task",
                    "fu_record",
                    "fu_contact_log",
                    "rpt_report",
                    "rpt_report_link",
                    "rpt_indicator_value",
                    "rpt_report_file",
                    "imp_row",
                    "st_node_occurrence",
                    "st_missing_field");

    private static final Pattern PATTERN = compile();

    private ControlledTables() {}

    public static boolean referencedBy(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return PATTERN.matcher(sql).find();
    }

    private static Pattern compile() {
        String joined = String.join("|", NAMES);
        return Pattern.compile("(?i)\\b(" + joined + ")\\b");
    }
}
