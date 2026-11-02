package com.crdp.followup.notice.app;

import com.crdp.followup.notice.domain.NotifyEventPayload;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.domain.RecipientOption;
import com.crdp.followup.notice.infra.mapper.NotifyRecipientMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 接收人规则解析（E09-T04 / A-NTF-004、C-NTF-004；hospital-a-spec §7「业务规则」）。
 *
 * <p>解析顺序：
 *
 * <ol>
 *   <li>事件负载显式给出 recipientIds（每日汇总由 E09-T05 作业按待办算好下发）→ 只保留启用用户直接采用；
 *   <li>OWNER：任务负责人（TASK_REASSIGNED = 新负责人、CALLBACK_DUE = 记录人）。发送前按病区范围复核： 用户须启用、且（未随事件给出病区 ||
 *       负责该病区 || 持全院范围角色）——已不负责该病区的医生不再收到；
 *   <li>负责人缺失或复核不通过 → WARD_STAFF 兜底：该病区全部启用成员（A 是医生与随访员、C 含护士， 成员都是经「负责病区」关联的医护，差异在关联了谁）；
 *   <li>CC_WARD_LEADER：追加病区「默认随访负责人」（A-WARD-001 字段提前物化；「病区负责人」概念规格未单列， OQ-60）。
 * </ol>
 *
 * <p>任何路径都只解析 sys_user 医护：首期不发给患者（A-NTF-004 / C-NTF-004 医院已确认）。
 */
@Component
public class NotifyRecipientResolver {

    /** 解析结果：去重有序接收人 + 说明（日志用，不含患者信息）。 */
    public record Resolution(List<Long> recipientIds, String note) {

        static Resolution of(List<Long> ids, String note) {
            return new Resolution(List.copyOf(ids), note);
        }
    }

    private final NotifyRecipientMapper mapper;

    public NotifyRecipientResolver(NotifyRecipientMapper mapper) {
        this.mapper = mapper;
    }

    /** 按规则选项与事件负载解析接收人；无合适接收人返回空清单（调用方记日志跳过，不算失败）。 */
    public Resolution resolve(
            NotifyEventType type, List<RecipientOption> options, NotifyEventPayload payload) {
        if (payload.recipientIds() != null && !payload.recipientIds().isEmpty()) {
            return explicitRecipients(payload.recipientIds());
        }
        LinkedHashSet<Long> resolved = new LinkedHashSet<>();
        boolean ownerAccepted = false;
        if (options.contains(RecipientOption.OWNER) && payload.ownerId() != null) {
            ownerAccepted = ownerPassesReview(payload.ownerId(), payload.wardId());
            if (ownerAccepted) {
                resolved.add(payload.ownerId());
            }
        }
        if (!ownerAccepted
                && options.contains(RecipientOption.WARD_STAFF)
                && payload.wardId() != null) {
            List<Long> staff = mapper.listEnabledWardStaff(payload.wardId());
            resolved.addAll(staff);
        }
        if (options.contains(RecipientOption.CC_WARD_LEADER) && payload.wardId() != null) {
            Long leader = mapper.findWardDefaultOwner(payload.wardId());
            if (leader != null && mapper.countEnabledUser(leader) > 0) {
                resolved.add(leader);
            }
        }
        return Resolution.of(new ArrayList<>(resolved), note(type, ownerAccepted, resolved.size()));
    }

    private Resolution explicitRecipients(List<Long> candidateIds) {
        List<Long> enabled =
                candidateIds.stream().filter(id -> mapper.countEnabledUser(id) > 0).toList();
        LinkedHashSet<Long> distinct = new LinkedHashSet<>(enabled);
        return Resolution.of(new ArrayList<>(distinct), "显式接收人 " + distinct.size() + " 人（负载下发）");
    }

    /** 负责人病区复核：启用 + （无病区锚点 || 负责该病区 || 全院范围）。 */
    private boolean ownerPassesReview(long ownerId, Long wardId) {
        if (mapper.countEnabledUser(ownerId) == 0) {
            return false;
        }
        if (wardId == null) {
            return true;
        }
        return mapper.countWardMembership(ownerId, wardId) > 0
                || mapper.countHospitalWideScope(ownerId) > 0;
    }

    private static String note(NotifyEventType type, boolean ownerAccepted, int size) {
        return type.label()
                + " 解析接收人 "
                + size
                + " 人（负责人"
                + (ownerAccepted ? "命中" : "缺失，按规则兜底")
                + "）";
    }
}
