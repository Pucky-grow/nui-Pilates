package com.yoyi.pilates.ledger;

import static com.yoyi.pilates.ledger.LedgerRules.*;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.infra.storage.FileService;
import com.yoyi.pilates.marketing.application.PointsService;
import com.yoyi.pilates.member.application.MemberService;
import com.yoyi.pilates.user.application.WxMpClient;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.entity.UserProfileEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserProfileMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StudioOfflineMembers {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LedgerStore db;
    private final StudioWalletService wallet;
    private final MemberService members;
    private final UserMapper users;
    private final UserProfileMapper profiles;
    private final StudioLedgerAccess access;
    private final PointsService points;
    private final FileService files;

    record Create(String nickname, String phone, String sourceRef, String mode) {}
    record Invite(long id, String code, LocalDateTime expiresAt) {}
    record Approve(String proof, String reason) {}

    @Transactional
    public long create(long actor, String key, Create input) {
        return db.command(actor, key, "MEMBER_CREATE", input, () -> {
            String name = required(input.nickname(), 64, "会员称呼");
            String phone = optional(input.phone(), 20, "手机号");
            if (phone != null && !phone.matches("1[3-9][0-9]{9}")) throw invalid("手机号格式无效");
            String source = optional(input.sourceRef(), 128, "旧台账会员编号");
            String mode = input.mode() == null ? wallet.defaultMode() : oneOf(input.mode(), "余额模式", "SHARED", "SPLIT");
            UserEntity user = new UserEntity();
            user.setNickname(name);
            user.setPhone(phone);
            user.setGender(0);
            user.setStatus("ACTIVE");
            users.insert(user);

            var profile = new UserProfileEntity();
            profile.setUserId(user.getId());
            profiles.insert(profile);
            members.ensureMember(user.getId());
            long id = db.memberForUser(user.getId());
            try {
                db.update("INSERT INTO studio_wallet_profile(member_id,mode,source_ref) VALUES(?,?,?)", id, mode, source);
            } catch (DuplicateKeyException ex) {
                throw conflict("旧台账会员编号已存在，请查找原会员");
            }
            db.audit(actor, "MEMBER_CREATE", id, Map.of("userId", user.getId(), "mode", mode));
            return id;
        });
    }

    Map<String, Object> member(long id) {
        return db.require("""
                SELECT m.id,m.user_id,m.member_no,m.status,u.nickname,
                       CASE WHEN u.openid IS NULL THEN 'UNBOUND' ELSE 'BOUND' END AS binding
                FROM member m JOIN `user` u ON u.id=m.user_id AND u.deleted=0 WHERE m.id=? AND m.deleted=0
                """, id).values();
    }

    long userId(long memberId) {
        return ((Number) member(memberId).get("user_id")).longValue();
    }

    @Transactional
    public Invite invite(long memberId, long actor) {
        db.lockMember(memberId);
        var user = unboundUser(memberId);
        db.update("""
                UPDATE studio_claim_invite SET state='REVOKED',claim_openid=NULL,claim_unionid=NULL
                WHERE member_id=? AND state IN ('ISSUED','REQUESTED')
                """, memberId);
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var expires = StudioTime.now().plusHours(24);
        long id = db.insert("""
                INSERT INTO studio_claim_invite(member_id,code_hash,state,expires_at) VALUES(?,?,'ISSUED',?)
                """, memberId, LedgerStore.digest(code), expires);
        db.audit(actor, "INVITE_ISSUE", id, Map.of("memberId", memberId, "userId", user.number("id")));
        return new Invite(id, code, expires);
    }

    private LedgerStore.Row unboundUser(long memberId) {
        var user = db.require("""
                SELECT u.id,u.openid,u.status,u.phone,u.avatar_url FROM `user` u JOIN member m ON m.user_id=u.id
                WHERE m.id=? AND m.deleted=0 AND u.deleted=0 FOR UPDATE
                """, memberId);
        if (user.text("openid") != null || !"ACTIVE".equals(user.text("status"))) {
            throw conflict("只有未绑定且启用的会员可认领");
        }
        return user;
    }

    private String hashCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{32}")) throw invalid("邀请无效或已过期");
        return LedgerStore.digest(code);
    }

    void checkInvite(String code) {
        var invitation = db.find("SELECT * FROM studio_claim_invite WHERE code_hash=?", hashCode(code));
        if (!valid(invitation)) throw invalid("邀请无效或已过期");
    }

    private boolean valid(LedgerStore.Row invite) {
        return invite != null && invite.time("expires_at").isAfter(StudioTime.now())
                && List.of("ISSUED", "REQUESTED").contains(invite.text("state"));
    }

    private String proof(LedgerStore.Row invite, String openid) {
        return LedgerStore.digest(invite.text("code_hash") + ":" + openid).substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> requestClaim(String code, WxMpClient.WxSession wx) {
        String hash = hashCode(code);
        var initial = db.find("SELECT * FROM studio_claim_invite WHERE code_hash=?", hash);
        if (!valid(initial)) throw invalid("邀请无效或已过期");
        long memberId = initial.number("member_id");
        db.lockMember(memberId);
        var offline = unboundUser(memberId);
        var invite = db.find("SELECT * FROM studio_claim_invite WHERE code_hash=? FOR UPDATE", hash);
        if (!valid(invite)) throw invalid("邀请无效或已过期");
        String openid = required(wx.getOpenid(), 64, "微信身份");
        if ("REQUESTED".equals(invite.text("state")) && !openid.equals(invite.text("claim_openid"))) {
            throw conflict("该邀请已用于申请，请联系门店");
        }
        loginOnlyUser(openid, offline);
        db.update("""
                UPDATE studio_claim_invite SET state='REQUESTED',claim_openid=?,claim_unionid=? WHERE id=?
                """, openid, optional(wx.getUnionid(), 64, "微信身份"), invite.number("id"));
        return Map.of("state", "REQUESTED", "proof", proof(invite, openid));
    }

    public boolean hasPendingClaim(String openid) {
        return access.enabled() && openid != null && db.find("""
                SELECT id FROM studio_claim_invite WHERE claim_openid=? AND state='REQUESTED' AND expires_at>?
                LIMIT 1
                """, openid, StudioTime.now()) != null;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long approve(long memberId, long id, long actor, String key, Approve input) {
        return db.command(actor, key, "INVITE_APPROVE", List.of(memberId, id, input), () -> {
            String reason = required(input.reason(), 255, "核对依据");
            db.lockMember(memberId);
            var user = unboundUser(memberId);
            var invite = db.find("SELECT * FROM studio_claim_invite WHERE member_id=? AND id=? FOR UPDATE", memberId, id);
            if (!valid(invite) || !"REQUESTED".equals(invite.text("state"))) throw conflict("邀请未申请或已失效");
            String openid = invite.text("claim_openid");
            if (!proof(invite, openid).equalsIgnoreCase(input.proof())) throw invalid("本人核对码不一致");
            UserEntity online = loginOnlyUser(openid, user);
            long offlineUserId = user.number("id");
            String unionid = invite.text("claim_unionid");
            Map<String, Object> audit = new LinkedHashMap<>(Map.of("memberId", memberId, "reason", reason));
            if (online != null) {
                retireLoginOnlyUser(online, user);
                if (unionid == null) unionid = online.getUnionid();
                audit.put("retiredUserId", online.getId());
            }
            try {
                db.update("UPDATE `user` SET openid=?,unionid=? WHERE id=?", openid, unionid, offlineUserId);
            } catch (DuplicateKeyException ex) {
                throw conflict("该微信已关联其他档案，请核对");
            }
            db.update("UPDATE studio_claim_invite SET state='CLAIMED',claim_openid=NULL,claim_unionid=NULL WHERE id=?", id);
            db.audit(actor, "INVITE_APPROVE", id, audit);
            return memberId;
        });
    }

    private UserEntity loginOnlyUser(String openid, LedgerStore.Row offline) {
        var account = db.find("SELECT id FROM `user` WHERE openid=?", openid);
        if (account == null) return null;
        long userId = account.number("id");
        // Administrative ledger writes take the member lock first; booking creation takes the user lock.
        var member = db.find("SELECT id FROM member WHERE user_id=? AND deleted=0 FOR UPDATE", userId);
        var online = users.lockById(userId);
        if (online == null || !openid.equals(online.getOpenid()) || !"ACTIVE".equals(online.getStatus()) || member == null) {
            throw conflict("该微信已有其他会员档案，请联系门店核对");
        }
        var phone = offline.text("phone");
        if (phone != null && online.getPhone() != null && !phone.equals(online.getPhone())) {
            throw conflict("微信账号与线下档案的手机号不同，请联系门店核对");
        }
        if (db.find("SELECT id FROM coach WHERE openid=? AND deleted=0", openid) != null
                || !isLoginOnlyMember(member.number("id"), openid, offline.number("id"))) {
            throw conflict("该微信已有独立预约、账目或其他资料，请联系门店核对两份档案");
        }
        return online;
    }

    private boolean isLoginOnlyMember(long memberId, String openid, long offlineUserId) {
        return db.find("""
                SELECT m.id FROM member m JOIN member_level ml ON ml.id=m.level_id AND ml.code='NORMAL'
                WHERE m.id=? AND m.status='ACTIVE' AND m.valid_until IS NULL AND m.last_training_at IS NULL
                  AND m.total_sessions=0 AND m.total_consumption=0
                  AND NOT EXISTS (SELECT 1 FROM studio_wallet_profile p WHERE p.member_id=m.id)
                  AND NOT EXISTS (SELECT 1 FROM studio_receipt r WHERE r.member_id=m.id)
                  AND NOT EXISTS (SELECT 1 FROM studio_member_lesson l WHERE l.member_id=m.id)
                  AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.user_id=m.user_id)
                  AND NOT EXISTS (SELECT 1 FROM waitlist w WHERE w.user_id=m.user_id AND w.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM `order` o WHERE o.user_id=m.user_id)
                  AND NOT EXISTS (SELECT 1 FROM user_package p WHERE p.user_id=m.user_id)
                  AND NOT EXISTS (SELECT 1 FROM points_record p WHERE p.user_id=m.user_id AND p.deleted=0
                    AND NOT (p.change_type='EARN' AND p.reason='注册' AND COALESCE(p.biz_type,'')='USER' AND COALESCE(p.biz_id,'')=?))
                  AND NOT EXISTS (SELECT 1 FROM user_coupon c WHERE c.user_id=m.user_id AND c.deleted=0
                    AND (c.used_at IS NOT NULL OR c.order_id IS NOT NULL OR c.status NOT IN ('UNUSED','EXPIRED')
                      OR EXISTS (SELECT 1 FROM user_coupon d WHERE d.user_id=? AND d.coupon_id=c.coupon_id AND d.deleted=0)))
                  AND NOT EXISTS (SELECT 1 FROM user_profile p WHERE p.user_id=m.user_id AND p.deleted=0
                    AND (p.real_name IS NOT NULL OR p.birthday IS NOT NULL OR p.height IS NOT NULL OR p.weight IS NOT NULL OR p.remark IS NOT NULL))
                  AND NOT EXISTS (SELECT 1 FROM user_privacy p WHERE p.user_id=m.user_id AND p.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM training_record r WHERE r.user_id=m.user_id AND r.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM body_assessment a WHERE a.user_id=m.user_id AND a.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM body_measurement b WHERE b.user_id=m.user_id AND b.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM course_review r WHERE r.user_id=m.user_id AND r.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM file_object f WHERE f.owner_type='MP_USER' AND f.owner_id=m.user_id AND f.purpose<>'AVATAR')
                  AND NOT EXISTS (SELECT 1 FROM invitation i WHERE (i.inviter_id=m.user_id OR i.invitee_id=m.user_id) AND i.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM article_like a WHERE a.user_id=m.user_id AND a.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM article_favorite a WHERE a.user_id=m.user_id AND a.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM feedback f WHERE f.user_id=m.user_id AND f.deleted=0)
                  AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.user_id=m.user_id AND n.deleted=0)
                """, memberId, openid, offlineUserId) != null;
    }

    private void retireLoginOnlyUser(UserEntity online, LedgerStore.Row offline) {
        long from = online.getId();
        long to = offline.number("id");
        String avatar = offline.text("avatar_url");
        if ((avatar == null || avatar.isBlank()) && online.getAvatarUrl() != null && !online.getAvatarUrl().isBlank()) {
            try {
                avatar = files.memberAvatar(files.normalize(online.getAvatarUrl()), from);
            } catch (BusinessException error) {
                // A legacy or inaccessible image stays on the archived record.
                if (error.getCode() != ResultCode.BAD_REQUEST.getCode() && error.getCode() != ResultCode.FORBIDDEN.getCode()
                        && error.getCode() != ResultCode.NOT_FOUND.getCode()) throw error;
            }
        }
        db.update("""
                UPDATE file_object SET owner_id=? WHERE owner_type='MP_USER' AND owner_id=? AND purpose='AVATAR'
                """, to, from);
        db.update("UPDATE user_coupon SET user_id=? WHERE user_id=? AND deleted=0", to, from);
        var welcome = db.find("""
                SELECT points FROM points_record WHERE user_id=? AND deleted=0
                    AND change_type='EARN' AND reason='注册' AND biz_type='USER' AND biz_id=? ORDER BY id DESC LIMIT 1
                """, from, online.getOpenid());
        if (welcome != null) points.earn(to, (int) welcome.number("points"), "注册", "USER", online.getOpenid());

        db.update("UPDATE member SET status='DISABLED',deleted=1 WHERE user_id=? AND deleted=0", from);
        db.update("UPDATE `user` SET openid=NULL,unionid=NULL,phone=NULL,status='DISABLED',deleted=1 WHERE id=?", from);
        String phone = offline.text("phone");
        db.update("UPDATE `user` SET phone=?,avatar_url=? WHERE id=?", phone != null ? phone : online.getPhone(), avatar, to);
    }

    @Transactional
    public long revoke(long memberId, long id, long actor, String key) {
        return db.command(actor, key, "INVITE_REVOKE", List.of(memberId, id), () -> {
            db.lockMember(memberId);
            if (db.update("""
                    UPDATE studio_claim_invite SET state='REVOKED',claim_openid=NULL,claim_unionid=NULL
                    WHERE member_id=? AND id=? AND state IN ('ISSUED','REQUESTED')
                    """, memberId, id) != 1) throw conflict("邀请已处理");
            db.audit(actor, "INVITE_REVOKE", id, Map.of("memberId", memberId));
            return id;
        });
    }

    List<Map<String, Object>> invites(long memberId) {
        return db.list("""
                SELECT id,state,expires_at FROM studio_claim_invite WHERE member_id=? ORDER BY id DESC LIMIT 10
                """, memberId).stream().map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>(row.values());
            if (List.of("ISSUED", "REQUESTED").contains(row.text("state")) && !row.time("expires_at").isAfter(StudioTime.now())) {
                safe.put("state", "EXPIRED");
            }
            return safe;
        }).toList();
    }
}
