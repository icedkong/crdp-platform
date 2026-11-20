-- OQR-04：敏感身份更正独立授权，默认不进入可分配范围、不向任何角色自动补授。
INSERT INTO plt_menu(id,parent_id,side,type,module_code,title,perm_code,sort_no,hidden,status)
VALUES (9501,21,'HOSPITAL','BUTTON','followup','更正敏感身份信息','followup:patient:correct',45,1,'ENABLED');
INSERT INTO plt_sensitive_perm(perm_code,name)
VALUES ('followup:patient:correct','患者敏感身份更正（原因、二次确认、摘要留痕）');
