-- E15-T02 / A-FU-005、C-FU-020：任务与随访记录的关联（「工作台可直接录入随访表单」）。
--
-- 任务开启录入（POST /api/hospital/followup/tasks/{id}/start）时创建 DRAFT 草稿记录
-- （fe_record，经 FormService.createRecord，OQ-77① 载体口径）并把 id 回填到本列；
-- 随访整页表单统一用 recordId 路由（pages.md §5「任务开启时已创建草稿记录，所以统一
-- 用 recordId」）。唯一键保证一条记录至多被一个任务引用；NULL = 尚未开始录入。
-- 提交（POST /records/{id}/submit）经本列找到任务并走 FollowupTaskStatusService.complete
-- （followup V53 的 completed_at / completed_by 即在该入口写入）。
-- fu_record（患者 / 任务关联 + @DataScope 的业务记录表）仍随 E15-T07 历史补录落地
-- （OQ-83① 暂行口径：HTTP 契约不变，届时迁移载体即可）。

ALTER TABLE fu_task
  ADD COLUMN record_id BIGINT NULL COMMENT '随访记录 fe_record.id（开始录入时创建草稿并回填，pages.md §5 统一用 recordId；E15-T02）',
  ADD UNIQUE KEY uk_fu_task_record (record_id);
