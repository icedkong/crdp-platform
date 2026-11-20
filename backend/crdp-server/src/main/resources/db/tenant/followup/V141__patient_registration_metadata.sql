-- OQR-04 / A-PAT-003、005 / C-PAT-011：登记信息实体适配元数据，不存患者 JSON。

INSERT INTO cfg_dict(code,name,status) VALUES ('patient_registration_gender','登记性别','ENABLED');

INSERT INTO cfg_dict_item(dict_code,item_code,label,sort_no,source,status) VALUES ('patient_registration_gender','M','男',10,'TENANT','ENABLED'),('patient_registration_gender','F','女',20,'TENANT','ENABLED');

INSERT INTO fe_form(code,name,usage_type,current_version,categories) VALUES ('PT_REG_A','登记信息','BASELINE',1,JSON_ARRAY(JSON_OBJECT('key','registration','label','登记信息','sortNo',10)));

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_A','gender','gender','性别','registration','select',1,0,'column','patient_registration_gender',10);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_A','birthDate','birthDate','出生日期','registration','date',0,0,'column',NULL,20);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_A','surgeryDate','surgeryDate','手术日期','registration','date',1,0,'column',NULL,30);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_A','admissionDate','admissionDate','入院日期','registration','date',1,0,'column',NULL,40);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_A','dischargeDate','dischargeDate','出院日期','registration','date',1,0,'column',NULL,50);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_A','patientType','patientType','患者类型','registration','select',0,0,'column','patient_type',60);

INSERT INTO fe_form_version(form_code,version,categories,field_count) SELECT code,1,categories,6 FROM fe_form WHERE code='PT_REG_A';

INSERT INTO fe_form(code,name,usage_type,current_version,categories) VALUES ('PT_REG_C','登记信息','BASELINE',1,JSON_ARRAY(JSON_OBJECT('key','registration','label','登记信息','sortNo',10)));

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_C','gender','gender','性别','registration','select',1,0,'column','patient_registration_gender',10);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_C','birthDate','birthDate','出生日期','registration','date',1,0,'column',NULL,20);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_C','surgeryDate','surgeryDate','手术日期','registration','date',0,0,'column',NULL,30);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_C','admissionDate','admissionDate','入院日期','registration','date',0,0,'column',NULL,40);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_C','dischargeDate','dischargeDate','出院日期','registration','date',0,0,'column',NULL,50);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_C','patientType','patientType','患者类型','registration','select',0,0,'column','patient_type',60);

INSERT INTO fe_form_version(form_code,version,categories,field_count) SELECT code,1,categories,6 FROM fe_form WHERE code='PT_REG_C';

INSERT INTO fe_form(code,name,usage_type,current_version,categories) VALUES ('PT_IDENTITY','敏感身份信息','BASELINE',1,JSON_ARRAY(JSON_OBJECT('key','identity','label','敏感身份信息','sortNo',10)));

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_IDENTITY','name','name','姓名','identity','text',1,1,'encrypted',NULL,10);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_IDENTITY','medicalRecordNo','medicalRecordNo','病案号 / 病历号','identity','text',1,1,'encrypted',NULL,20);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_IDENTITY','idCard','idCard','证件号','identity','text',0,1,'encrypted',NULL,30);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_IDENTITY','address','address','住址','identity','textarea',0,1,'encrypted',NULL,40);

INSERT INTO fe_form_version(form_code,version,categories,field_count) SELECT code,1,categories,4 FROM fe_form WHERE code='PT_IDENTITY';

INSERT INTO fe_form(code,name,usage_type,current_version,categories) VALUES ('PT_REG_STATUS','归属与状态（只读）','BASELINE',1,JSON_ARRAY(JSON_OBJECT('key','status','label','归属与状态（只读）','sortNo',10)));

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_STATUS','wardName','wardName','病区','status','text',0,0,'column',NULL,10);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_STATUS','responsibleUserName','responsibleUserName','责任医生','status','text',0,0,'column',NULL,20);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_STATUS','followupStatus','followupStatus','随访状态','status','text',0,0,'column',NULL,30);

INSERT INTO fe_field(form_code,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no) VALUES ('PT_REG_STATUS','dischargeStatus','dischargeStatus','出院状态','status','text',0,0,'column',NULL,40);

INSERT INTO fe_form_version(form_code,version,categories,field_count) SELECT code,1,categories,4 FROM fe_form WHERE code='PT_REG_STATUS';

INSERT INTO fe_form_version_field
(version_id,form_code,version,field_key,field_code,label,category_key,type,required,sensitive_flag,storage_type,dict_code,sort_no)
SELECT v.id,f.form_code,1,f.field_key,f.field_code,f.label,f.category_key,f.type,f.required,f.sensitive_flag,f.storage_type,f.dict_code,f.sort_no
FROM fe_field f JOIN fe_form_version v ON v.form_code=f.form_code AND v.version=1
WHERE f.form_code IN ('PT_REG_A','PT_REG_C','PT_IDENTITY','PT_REG_STATUS');

INSERT INTO cfg_dict(code,name,status) VALUES ('patient_registration_discharge','出院状态','ENABLED'),('patient_registration_status','随访状态','ENABLED');
INSERT INTO cfg_dict_item(dict_code,item_code,label,sort_no,source,status) VALUES
('patient_registration_discharge','1','存活',10,'TENANT','ENABLED'),('patient_registration_discharge','2','病重自动出院',20,'TENANT','ENABLED'),('patient_registration_discharge','3','死亡',30,'TENANT','ENABLED'),
('patient_registration_status','ACTIVE','在随访',10,'TENANT','ENABLED'),('patient_registration_status','PAUSED','暂停',20,'TENANT','ENABLED'),('patient_registration_status','DEAD','死亡',30,'TENANT','ENABLED'),('patient_registration_status','TERMINATED','停止随访',40,'TENANT','ENABLED'),('patient_registration_status','LOST','失访',50,'TENANT','ENABLED'),('patient_registration_status','FINISHED','已完成',60,'TENANT','ENABLED');
UPDATE fe_field SET type='select',dict_code='patient_registration_discharge' WHERE form_code='PT_REG_STATUS' AND field_key='dischargeStatus';
UPDATE fe_field SET type='select',dict_code='patient_registration_status' WHERE form_code='PT_REG_STATUS' AND field_key='followupStatus';
UPDATE fe_form_version_field SET type='select',dict_code='patient_registration_discharge' WHERE form_code='PT_REG_STATUS' AND field_key='dischargeStatus';
UPDATE fe_form_version_field SET type='select',dict_code='patient_registration_status' WHERE form_code='PT_REG_STATUS' AND field_key='followupStatus';
