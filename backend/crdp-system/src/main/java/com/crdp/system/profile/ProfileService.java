package com.crdp.system.profile;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SmsCodeStore;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.HospitalMapper;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * E06-T11 / PLT-ORG-011：个人中心——基本信息（显示名）、换绑手机号（短信验证到<b>新</b>手机号）、 本人登录记录。修改密码与在线会话已由 E04-T05 /
 * E04-T06 落地（/auth/password、/auth/sessions），前端在个人中心聚合展示。
 *
 * <p>安全口径：手机号密文 + 盲索引存储（换绑同口径覆盖，本院唯一冲突 40902）； 验证码与登录 / 重置分命名空间（rebind:租户:账号:新手机号），钓鱼拿到的登录码不能换绑；
 * 审计与日志里的手机号一律脱敏（AGENTS.md 第 5 节）。
 */
@Service
public class ProfileService {

    /** 与 DemoPhoneInitializer / AuthService 的医院端手机号加密 purpose 一致（解密同一密钥位）。 */
    private static final String PHONE_PURPOSE = "sys-user-phone";

    /** 大陆手机号口径（与登录短信一致，演示段 1390000xxxx 同样匹配）。 */
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private static final int DISPLAY_NAME_MAX = 64;

    /** 登录记录一次返回的条数（个人中心展示用，不提供翻页）。 */
    private static final int LOGIN_LOG_LIMIT = 20;

    /** 登录 / 活跃时间展示口径：ISO-8601 带时区，固定北京时间（与 SessionAdminService 一致）。 */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Shanghai"));

    private final HospitalDirectory directory;
    private final HospitalMapper mapper;
    private final CryptoService cryptoService;
    private final SmsCodeStore smsCodeStore;
    private final SmsCodeSender smsCodeSender;
    private final AuditLogger audit;

    public ProfileService(
            HospitalDirectory directory,
            HospitalMapper mapper,
            CryptoService cryptoService,
            SmsCodeStore smsCodeStore,
            SmsCodeSender smsCodeSender,
            AuditLogger audit) {
        this.directory = directory;
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.smsCodeStore = smsCodeStore;
        this.smsCodeSender = smsCodeSender;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 基本信息视图。phoneMasked 为脱敏手机号（未绑定为 null），roles / tenantName 来自会话。 */
    public record ProfileView(
            String username,
            String displayName,
            String phoneMasked,
            List<String> roles,
            String tenantName,
            String createdAt) {}

    /** 登录记录行。message 为后端记录的原因代码（如 ok / bad-password），前端按成功 / 失败展示。 */
    public record LoginLogView(boolean success, String message, String createdAt) {}

    /** 读本人信息（须在本人租户上下文）。 */
    public ProfileView view(SessionPrincipal principal) {
        HospitalAccount account = requireAccount(principal);
        return new ProfileView(
                account.username(),
                account.displayName(),
                maskedPhone(decryptOrNull(account.phone())),
                principal.roles(),
                principal.tenantName(),
                formatTime(mapper.createdAt(account.id())));
    }

    /** 本人最近登录记录（只看自己的，按用户名过滤）。 */
    public List<LoginLogView> loginLogs(SessionPrincipal principal) {
        return mapper.loginLogs(principal.username(), LOGIN_LOG_LIMIT).stream()
                .map(
                        row ->
                                new LoginLogView(
                                        row.getSuccess(),
                                        row.getMessage(),
                                        formatTime(row.getCreatedAt())))
                .toList();
    }

    /** 修改显示名（PLT-ORG-011 基本信息；账号不可改）。 */
    public ProfileView updateDisplayName(SessionPrincipal principal, String displayName) {
        String name = displayName == null ? null : displayName.trim();
        if (name == null || name.isEmpty()) {
            throw new ApiException(400, 40001, "姓名不能为空");
        }
        if (name.length() > DISPLAY_NAME_MAX) {
            throw new ApiException(400, 40001, "姓名不能超过 " + DISPLAY_NAME_MAX + " 个字符");
        }
        HospitalAccount account = requireAccount(principal);
        mapper.updateDisplayName(account.id(), name);
        audit.record(
                new AuditEvent(
                        "USER_PROFILE_UPDATE",
                        account.id(),
                        "UPDATE",
                        "sys_user:" + account.username(),
                        "个人中心：修改姓名（原 " + account.displayName() + "）"));
        return view(principal);
    }

    /** 换绑第一步：把验证码发到新手机号。 60 秒冷却与每日上限由 SmsCodeStore 统一口径（超限 42900）； 新号与当前号相同直接 40001（避免无意义触达）。 */
    public void requestPhoneCode(SessionPrincipal principal, String newPhone) {
        requirePhone(newPhone);
        HospitalAccount account = requireAccount(principal);
        String current = decryptOrNull(account.phone());
        if (newPhone.equals(current)) {
            throw new ApiException(400, 40001, "新手机号不能与当前手机号相同");
        }
        String code = smsCodeStore.issue(bindCodeIdentifier(principal, newPhone), newPhone);
        smsCodeSender.sendPhoneBindCode(newPhone, code);
    }

    /** 换绑第二步：验证码通过后落新密文与盲索引。 验证码错误 / 过期统一 40102；新号已被本院其他账号绑定 40902；写审计（脱敏）。 */
    public ProfileView rebindPhone(SessionPrincipal principal, String newPhone, String code) {
        requirePhone(newPhone);
        HospitalAccount account = requireAccount(principal);
        smsCodeStore.verify(bindCodeIdentifier(principal, newPhone), code);
        String phoneIdx = cryptoService.blindIndex(PHONE_PURPOSE, newPhone);
        HospitalAccount holder = directory.findByPhoneIdx(phoneIdx).orElse(null);
        if (holder != null && holder.id() != account.id()) {
            throw new ApiException(409, 40902, "该手机号已绑定本院其他账号", maskedPhone(newPhone));
        }
        String oldMasked = maskedPhone(decryptOrNull(account.phone()));
        if (holder == null) {
            // 已是本人绑定（幂等重放）时不重复写库，仍按成功返回
            try {
                mapper.updatePhone(
                        account.id(), cryptoService.encrypt(PHONE_PURPOSE, newPhone), phoneIdx);
            } catch (DuplicateKeyException ex) {
                // 并发换绑撞上本院唯一约束：与预检查同口径 40902
                throw new ApiException(409, 40902, "该手机号已绑定本院其他账号");
            }
        }
        audit.record(
                new AuditEvent(
                        "USER_PHONE_REBIND",
                        account.id(),
                        "UPDATE",
                        "sys_user:" + account.username(),
                        "个人中心：换绑手机号（" + oldMasked + " → " + maskedPhone(newPhone) + "）"));
        return view(principal);
    }

    // ---------------------------------------------------------------- 私有

    private HospitalAccount requireAccount(SessionPrincipal principal) {
        HospitalAccount account = directory.findById(principal.userId()).orElse(null);
        if (account == null || !"ENABLED".equals(account.status())) {
            // 会话有效但账号已被停用 / 删除：按未登录口径拒绝，不暴露细节
            throw new ApiException(401, 40100, "未登录或登录已失效");
        }
        return account;
    }

    /** 换绑验证码命名空间：按租户 + 账号 + 新手机号隔离，与登录（login:）/ 重置（reset:）互不通用。 */
    private static String bindCodeIdentifier(SessionPrincipal principal, String newPhone) {
        return "rebind:" + principal.tenantCode() + ":" + principal.username() + ":" + newPhone;
    }

    private String decryptOrNull(String cipher) {
        return cipher == null ? null : cryptoService.decrypt(cipher);
    }

    private static void requirePhone(String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new ApiException(400, 40001, "手机号格式不正确");
        }
    }

    /** 审计与视图里的手机号一律脱敏（AGENTS.md 第 5 节）。 */
    static String maskedPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : TIME.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }
}
