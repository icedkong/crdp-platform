package com.crdp.followup.implant.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 植入记录表 pt_implant（E21-T01 / C-PAT-004；表 followup V81）。
 *
 * <p>查询全部为「当前用户数据范围内」口径（{@code @DataScope(resource = "patient")}：拦截器按 pt_patient 锚点以
 * pt_implant.patient_id EXISTS 改写，越范围行不可见）；插入与更新不走查询拦截， 写入口的可见性校验由应用层经网关的 {@code @DataScope}
 * 查询先行完成（与 PatientContactMapper 同口径）。
 */
@Mapper
public interface ImplantMapper {

    /** 植入记录行（列表与编辑数据源；字段即 pt_implant 实体列，状态与结束信息由系统维护）。 */
    record ImplantRow(
            Long id,
            Long patientId,
            String implantType,
            LocalDate implantDate,
            String deviceType,
            String brand,
            String model,
            String serialNo,
            String pacingMode,
            String hospital,
            String doctor,
            String indication,
            String indicationNote,
            String status,
            LocalDate endDate,
            String endReason,
            Integer version,
            Long createdBy,
            Long updatedBy) {}

    /** 全部植入记录（按患者；日期新在前、同日 id 大在前——当前器械置顶由应用层按 status 排出）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, implant_type AS implantType,
                   implant_date AS implantDate, device_type AS deviceType, brand, model,
                   serial_no AS serialNo, pacing_mode AS pacingMode, hospital, doctor,
                   indication, indication_note AS indicationNote, status,
                   end_date AS endDate, end_reason AS endReason, version,
                   created_by AS createdBy, updated_by AS updatedBy
            FROM pt_implant
            WHERE patient_id = #{patientId}
            ORDER BY implant_date DESC, id DESC
            """)
    List<ImplantRow> findImplants(@Param("patientId") long patientId);

    /** 单条植入记录（编辑入口；越范围 / 不存在 null，上层 40400）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, implant_type AS implantType,
                   implant_date AS implantDate, device_type AS deviceType, brand, model,
                   serial_no AS serialNo, pacing_mode AS pacingMode, hospital, doctor,
                   indication, indication_note AS indicationNote, status,
                   end_date AS endDate, end_reason AS endReason, version,
                   created_by AS createdBy, updated_by AS updatedBy
            FROM pt_implant
            WHERE id = #{implantId}
            """)
    ImplantRow findImplant(@Param("implantId") long implantId);

    /** 当前在用植入记录（C-PAT-007「状态为在用的即当前器械」；无在用返回 null）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, implant_type AS implantType,
                   implant_date AS implantDate, device_type AS deviceType, brand, model,
                   serial_no AS serialNo, pacing_mode AS pacingMode, hospital, doctor,
                   indication, indication_note AS indicationNote, status,
                   end_date AS endDate, end_reason AS endReason, version,
                   created_by AS createdBy, updated_by AS updatedBy
            FROM pt_implant
            WHERE patient_id = #{patientId} AND status = 'ACTIVE'
            """)
    ImplantRow findActiveImplant(@Param("patientId") long patientId);

    /** 新建植入记录（在用唯一由 uk_pt_implant_active 生成列约束兜底，并发双开在用撞唯一键）。 */
    @Insert(
            """
            INSERT INTO pt_implant
              (patient_id, implant_type, implant_date, device_type, brand, model, serial_no,
               pacing_mode, hospital, doctor, indication, indication_note, status,
               end_date, end_reason, version, created_by)
            VALUES
              (#{row.patientId}, #{row.implantType}, #{row.implantDate}, #{row.deviceType},
               #{row.brand}, #{row.model}, #{row.serialNo}, #{row.pacingMode}, #{row.hospital},
               #{row.doctor}, #{row.indication}, #{row.indicationNote}, #{row.status},
               #{row.endDate}, #{row.endReason}, 0, #{row.createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertImplant(@Param("row") ImplantInsert row);

    /** 编辑保存（乐观锁：WHERE version = 基线版本，命中 0 行由应用层按 40900 冲突处理）。 */
    @Update(
            """
            UPDATE pt_implant
            SET implant_type = #{row.implantType}, implant_date = #{row.implantDate},
                device_type = #{row.deviceType}, brand = #{row.brand}, model = #{row.model},
                serial_no = #{row.serialNo}, pacing_mode = #{row.pacingMode},
                hospital = #{row.hospital}, doctor = #{row.doctor},
                indication = #{row.indication}, indication_note = #{row.indicationNote},
                version = version + 1, updated_by = #{row.updatedBy}
            WHERE id = #{row.id} AND version = #{row.baseVersion}
            """)
    int updateImplant(@Param("row") ImplantUpdate row);

    /** 结束在用记录（新建更新 / 升级时旧记录自动结束，结束日期 = 新植入日期，C 规格 §4 业务规则）。 */
    @Update(
            """
            UPDATE pt_implant
            SET status = 'REPLACED', end_date = #{endDate}, end_reason = #{endReason},
                version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{implantId} AND status = 'ACTIVE'
            """)
    int supersedeActive(
            @Param("implantId") long implantId,
            @Param("endDate") LocalDate endDate,
            @Param("endReason") String endReason,
            @Param("updatedBy") long updatedBy);

    /** 电极行（列表与编辑数据源；pt_lead 实体列）。 */
    record LeadRow(
            Long id,
            Long patientId,
            String chamber,
            String position,
            String model,
            LocalDate implantDate,
            String status,
            Integer version) {}

    /** 某患者的全部电极（按腔室 RA / RV / LV、植入日期排序）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT l.id, l.patient_id AS patientId, l.chamber, l.position, l.model,
                   l.implant_date AS implantDate, l.status, l.version
            FROM pt_lead l
            WHERE l.patient_id = #{patientId}
            ORDER BY FIELD(l.chamber, 'RA', 'RV', 'LV'), l.implant_date, l.id
            """)
    List<LeadRow> findLeads(@Param("patientId") long patientId);

    /** 某植入记录下的电极（经 pt_implant_lead 关联；沿用 = 同一电极挂多条植入记录，E21-T02）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT l.id, l.patient_id AS patientId, l.chamber, l.position, l.model,
                   l.implant_date AS implantDate, l.status, l.version
            FROM pt_lead l
            JOIN pt_implant_lead il ON il.lead_id = l.id
            WHERE il.implant_id = #{implantId}
            ORDER BY FIELD(l.chamber, 'RA', 'RV', 'LV'), l.implant_date, l.id
            """)
    List<LeadRow> findLeadsOfImplant(@Param("implantId") long implantId);

    /** 新建电极（每根一行；与植入记录的关联写 pt_implant_lead）。 */
    @Insert(
            """
            INSERT INTO pt_lead
              (patient_id, chamber, position, model, implant_date, status, version, created_by)
            VALUES
              (#{row.patientId}, #{row.chamber}, #{row.position}, #{row.model},
               #{row.implantDate}, #{row.status}, 0, #{row.createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertLead(@Param("row") LeadInsert row);

    /** 电极行编辑（乐观锁同植入记录口径）。 */
    @Update(
            """
            UPDATE pt_lead
            SET chamber = #{row.chamber}, position = #{row.position}, model = #{row.model},
                implant_date = #{row.implantDate}, status = #{row.status},
                version = version + 1, updated_by = #{row.updatedBy}
            WHERE id = #{row.id} AND version = #{row.baseVersion}
            """)
    int updateLead(@Param("row") LeadUpdate row);

    /** 删除未被任何植入记录引用的电极行（编辑中移除的录入行；仍有引用时由应用层阻止）。 */
    @Delete(
            """
            DELETE l FROM pt_lead l
            WHERE l.id = #{leadId} AND l.patient_id = #{patientId}
              AND NOT EXISTS (SELECT 1 FROM pt_implant_lead il WHERE il.lead_id = l.id)
            """)
    int deleteUnlinkedLead(@Param("leadId") long leadId, @Param("patientId") long patientId);

    /** 建立植入记录-电极关联。 */
    @Insert(
            """
            INSERT IGNORE INTO pt_implant_lead (patient_id, implant_id, lead_id)
            VALUES (#{patientId}, #{implantId}, #{leadId})
            """)
    int linkLead(
            @Param("patientId") long patientId,
            @Param("implantId") long implantId,
            @Param("leadId") long leadId);

    /** 解除植入记录-电极关联（编辑移除；电极行本身由 deleteUnlinkedLead 清理）。 */
    @Delete(
            """
            DELETE FROM pt_implant_lead
            WHERE implant_id = #{implantId} AND lead_id = #{leadId}
            """)
    int unlinkLead(@Param("implantId") long implantId, @Param("leadId") long leadId);

    /**
     * 电极是否仍被其他植入记录引用（编辑移除时判定电极行可否删除）。编辑流程内部一致性检查： 同一事务内、患者与植入记录已按 {@code @DataScope}
     * 校验过可见性（ImplantService.update），此处按链接行计数不再重复范围改写。
     */
    @IgnoreScope(reason = "编辑植入记录移除电极时判定链接行是否清零：同一事务内患者可见性已由 @DataScope 校验（ImplantService.update）")
    @Select(
            """
            SELECT COUNT(*) FROM pt_implant_lead WHERE lead_id = #{leadId}
            """)
    int linkCountOfLead(@Param("leadId") long leadId);

    /** 植入记录-电极关联行（列表分桶用）。 */
    record ImplantLinkRow(Long implantId, Long leadId) {}

    /** 患者的全部关联（范围内；越范围空清单）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT implant_id AS implantId, lead_id AS leadId
            FROM pt_implant_lead
            WHERE patient_id = #{patientId}
            """)
    List<ImplantLinkRow> findLinksOfPatient(@Param("patientId") long patientId);

    // ---------------------------------------------------------------- 插入 / 更新行对象

    /** pt_implant 插入行（useGeneratedKeys 回填 id，与 ContactVersionInsert 同款）。 */
    class ImplantInsert {
        private Long id;
        private long patientId;
        private String implantType;
        private LocalDate implantDate;
        private String deviceType;
        private String brand;
        private String model;
        private String serialNo;
        private String pacingMode;
        private String hospital;
        private String doctor;
        private String indication;
        private String indicationNote;
        private String status;
        private LocalDate endDate;
        private String endReason;
        private Long createdBy;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getPatientId() {
            return patientId;
        }

        public void setPatientId(long patientId) {
            this.patientId = patientId;
        }

        public String getImplantType() {
            return implantType;
        }

        public void setImplantType(String implantType) {
            this.implantType = implantType;
        }

        public LocalDate getImplantDate() {
            return implantDate;
        }

        public void setImplantDate(LocalDate implantDate) {
            this.implantDate = implantDate;
        }

        public String getDeviceType() {
            return deviceType;
        }

        public void setDeviceType(String deviceType) {
            this.deviceType = deviceType;
        }

        public String getBrand() {
            return brand;
        }

        public void setBrand(String brand) {
            this.brand = brand;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSerialNo() {
            return serialNo;
        }

        public void setSerialNo(String serialNo) {
            this.serialNo = serialNo;
        }

        public String getPacingMode() {
            return pacingMode;
        }

        public void setPacingMode(String pacingMode) {
            this.pacingMode = pacingMode;
        }

        public String getHospital() {
            return hospital;
        }

        public void setHospital(String hospital) {
            this.hospital = hospital;
        }

        public String getDoctor() {
            return doctor;
        }

        public void setDoctor(String doctor) {
            this.doctor = doctor;
        }

        public String getIndication() {
            return indication;
        }

        public void setIndication(String indication) {
            this.indication = indication;
        }

        public String getIndicationNote() {
            return indicationNote;
        }

        public void setIndicationNote(String indicationNote) {
            this.indicationNote = indicationNote;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public void setEndDate(LocalDate endDate) {
            this.endDate = endDate;
        }

        public String getEndReason() {
            return endReason;
        }

        public void setEndReason(String endReason) {
            this.endReason = endReason;
        }

        public Long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(Long createdBy) {
            this.createdBy = createdBy;
        }
    }

    /** pt_implant 更新行（乐观锁基线版本 baseVersion）。 */
    class ImplantUpdate {
        private long id;
        private int baseVersion;
        private String implantType;
        private LocalDate implantDate;
        private String deviceType;
        private String brand;
        private String model;
        private String serialNo;
        private String pacingMode;
        private String hospital;
        private String doctor;
        private String indication;
        private String indicationNote;
        private long updatedBy;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public int getBaseVersion() {
            return baseVersion;
        }

        public void setBaseVersion(int baseVersion) {
            this.baseVersion = baseVersion;
        }

        public String getImplantType() {
            return implantType;
        }

        public void setImplantType(String implantType) {
            this.implantType = implantType;
        }

        public LocalDate getImplantDate() {
            return implantDate;
        }

        public void setImplantDate(LocalDate implantDate) {
            this.implantDate = implantDate;
        }

        public String getDeviceType() {
            return deviceType;
        }

        public void setDeviceType(String deviceType) {
            this.deviceType = deviceType;
        }

        public String getBrand() {
            return brand;
        }

        public void setBrand(String brand) {
            this.brand = brand;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSerialNo() {
            return serialNo;
        }

        public void setSerialNo(String serialNo) {
            this.serialNo = serialNo;
        }

        public String getPacingMode() {
            return pacingMode;
        }

        public void setPacingMode(String pacingMode) {
            this.pacingMode = pacingMode;
        }

        public String getHospital() {
            return hospital;
        }

        public void setHospital(String hospital) {
            this.hospital = hospital;
        }

        public String getDoctor() {
            return doctor;
        }

        public void setDoctor(String doctor) {
            this.doctor = doctor;
        }

        public String getIndication() {
            return indication;
        }

        public void setIndication(String indication) {
            this.indication = indication;
        }

        public String getIndicationNote() {
            return indicationNote;
        }

        public void setIndicationNote(String indicationNote) {
            this.indicationNote = indicationNote;
        }

        public long getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(long updatedBy) {
            this.updatedBy = updatedBy;
        }
    }

    /** pt_lead 插入行。 */
    class LeadInsert {
        private Long id;
        private long patientId;
        private String chamber;
        private String position;
        private String model;
        private LocalDate implantDate;
        private String status;
        private Long createdBy;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getPatientId() {
            return patientId;
        }

        public void setPatientId(long patientId) {
            this.patientId = patientId;
        }

        public String getChamber() {
            return chamber;
        }

        public void setChamber(String chamber) {
            this.chamber = chamber;
        }

        public String getPosition() {
            return position;
        }

        public void setPosition(String position) {
            this.position = position;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public LocalDate getImplantDate() {
            return implantDate;
        }

        public void setImplantDate(LocalDate implantDate) {
            this.implantDate = implantDate;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(Long createdBy) {
            this.createdBy = createdBy;
        }
    }

    /** pt_lead 更新行（乐观锁基线版本 baseVersion）。 */
    class LeadUpdate {
        private long id;
        private long patientId;
        private int baseVersion;
        private String chamber;
        private String position;
        private String model;
        private LocalDate implantDate;
        private String status;
        private long updatedBy;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getPatientId() {
            return patientId;
        }

        public void setPatientId(long patientId) {
            this.patientId = patientId;
        }

        public int getBaseVersion() {
            return baseVersion;
        }

        public void setBaseVersion(int baseVersion) {
            this.baseVersion = baseVersion;
        }

        public String getChamber() {
            return chamber;
        }

        public void setChamber(String chamber) {
            this.chamber = chamber;
        }

        public String getPosition() {
            return position;
        }

        public void setPosition(String position) {
            this.position = position;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public LocalDate getImplantDate() {
            return implantDate;
        }

        public void setImplantDate(LocalDate implantDate) {
            this.implantDate = implantDate;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public long getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(long updatedBy) {
            this.updatedBy = updatedBy;
        }
    }
}
