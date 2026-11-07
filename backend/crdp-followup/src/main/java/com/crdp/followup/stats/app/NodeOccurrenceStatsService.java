package com.crdp.followup.stats.app;

import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 节点随访统计查询（E19-T01 / A-DASH-012；「一个口径一个查询」，docs/api/stats.md §1）： 按节点 × 第 N
 * 次出应随访、已随访数，数据来自每日刷新的节点随访事实表（{@link NodeOccurrenceRefreshService}）。
 *
 * <p>E19-T04 的节点随访率卡片（GET /api/hospital/followup/stats/node-follow-rate）与每年随访率（年度节点， A-DASH-006 与
 * A-DASH-012 同一口径）以本服务为唯一取数口，保证两处数字一致；HTTP 控制器随 E19-T04 落地， 本任务先提供服务层入口与聚合查询（数据范围由 Mapper 语句上的
 * {@code @DataScope(resource = "patient")} 按患者病区出行；范围为空返回空列表，不报错）。
 */
@Service
public class NodeOccurrenceStatsService {

    private final NodeOccurrenceMapper occurrenceMapper;

    public NodeOccurrenceStatsService(NodeOccurrenceMapper occurrenceMapper) {
        this.occurrenceMapper = occurrenceMapper;
    }

    /**
     * 一行 = 一个节点的一个次数（固定节点各一行、循环节点按第 N 次分行，A-DASH-012「节点」口径）： 应随访、 已随访（含逾期完成 / 补录 /
     * 历史导入）、窗口内完成（参考）、失访、死亡人数；按计划内排序返回。 只列有应随访患者的次数（聚合天然满足）。
     */
    public record NodeCountView(
            long nodeId,
            String nodeName,
            int occurrence,
            long dueCount,
            long followedCount,
            long inWindowCount,
            long lostCount,
            long deathCount) {}

    /** 按计划版本聚合（planId = fu_plan.id；筛选条件随 E19-T04 卡片接口扩展病区 / 队列）。 */
    public List<NodeCountView> nodeCounts(long planId) {
        return occurrenceMapper.aggregateByNode(planId).stream()
                .map(
                        row ->
                                new NodeCountView(
                                        row.nodeId(),
                                        row.nodeName(),
                                        row.occurrence(),
                                        row.dueCount(),
                                        row.followedCount(),
                                        row.inWindowCount(),
                                        row.lostCount(),
                                        row.deathCount()))
                .toList();
    }
}
