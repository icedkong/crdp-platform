-- E21-T11 / C-FU-026（Q17）：登记「修改已提交记录」与「删除随访记录」两个独立按钮权限。
--   * 229「修改已提交记录」（followup:record:edit-submitted，随访任务 22 下）：修改已提交的随访记录
--     （POST /records/{id}/edit-session、PUT /records/{id}）。C 规格权限点表原有码（敏感）；
--     A 规格无该码——A 修改已提交沿用 222 的 followup:record:edit（OQ-14：按租户权限表各用各的码，
--     运行期用功能开关 record_edit_submitted 区分，租户库 followup V87 注释）。
--   * 230「删除随访记录」（followup:record:delete，随访任务 22 下）：软删除随访记录、须填原因并记审计
--     （DELETE /records/{id}）。C 规格权限点表原有码（敏感）；A 规格权限表同为敏感码（followup:record:delete）。
--   两码均敏感（C 规格 §10、A 规格权限表）→ 登记 plt_sensitive_perm：默认不可分配，平台按租户单独放开
--   （PLT-SCOPE-004，与 217 删除患者同口径），不进授权范围模板；放开后由租户管理员分配给角色或个人
--   （Q17：谁有权限谁可操作，预置角色分配只是默认值）。接口资源扫描（PLT-PERM-004）按注解自动登记
--   plt_api_resource，无需种子行。
--   followup:record:delete 在 V18 已登记 plt_sensitive_perm（「删除业务数据」），本脚本 INSERT IGNORE
--   幂等并存（名称沿用本行更具体的表述，已有行不改）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (229, 22, 'HOSPITAL', 'BUTTON', 'followup', '修改已提交记录', NULL, NULL, NULL, 'followup:record:edit-submitted', NULL, 20, 1, 'ENABLED'),
  (230, 22, 'HOSPITAL', 'BUTTON', 'followup', '删除随访记录', NULL, NULL, NULL, 'followup:record:delete', NULL, 30, 1, 'ENABLED');

-- 敏感权限清单（uk_plt_sensitive_perm 幂等）：命中按钮默认不可分配，平台按租户单独放开（PLT-SCOPE-004）。
INSERT IGNORE INTO plt_sensitive_perm (perm_code, name) VALUES
  ('followup:record:edit-submitted', '修改已提交的随访记录（留字段级历史，Q17）'),
  ('followup:record:delete', '删除随访记录（软删除、填原因、记入审计，Q17）');
