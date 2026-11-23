package com.yoyi.pilates.user.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.member.application.MemberService;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.system.application.RbacService;
import com.yoyi.pilates.system.infrastructure.entity.SysUserEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SysUserMapper;
import com.yoyi.pilates.system.security.CoachBinding;
import com.yoyi.pilates.system.security.JwtService;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.entity.UserProfileEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserProfileMapper;
import com.yoyi.pilates.user.interfaces.dto.TokenResponse;
import com.yoyi.pilates.user.interfaces.dto.WxLoginRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证服务: 微信登录 / 后台登录 / 登出.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final WxMpClient wxMpClient;
    private final UserMapper userMapper;
    private final UserProfileMapper userProfileMapper;
    private final SysUserMapper sysUserMapper;
    private final MemberService memberService;
    private final com.yoyi.pilates.ledger.StudioOfflineMembers offlineMembers;
    private final JwtService jwtService;
    private final RbacService rbacService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final EventPublisher eventPublisher;
    private final com.yoyi.pilates.system.security.LoginProtection protection;
    private String dummyHash;
    @jakarta.annotation.PostConstruct void prepareDummyHash(){dummyHash=passwordEncoder.encode(java.util.UUID.randomUUID().toString());}
    private final ObjectProvider<CoachBinding> coachBindingProvider;

    /**
     * 微信小程序登录: code → openid → 查/建用户(+会员档案) → 签发 JWT.
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TokenResponse wxLogin(WxLoginRequest request) {
        WxMpClient.WxSession session = wxMpClient.code2session(request.getCode());

        UserEntity user = userMapper.lockByOpenid(session.getOpenid());
        boolean isNew = user == null;
        if (isNew) {
            if (offlineMembers.hasPendingClaim(session.getOpenid())) {
                throw BusinessException.of(ResultCode.CONFLICT, "会员绑定申请待门店确认");
            }
            user = new UserEntity();
            user.setOpenid(session.getOpenid());
            user.setUnionid(session.getUnionid());
            user.setNickname(StringUtils.defaultIfBlank(request.getNickname(), "优仪会员"));
            user.setAvatarUrl(request.getAvatarUrl());
            user.setGender(request.getGender() == null ? 0 : request.getGender());
            user.setStatus("ACTIVE");
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.insert(user);

            UserProfileEntity profile = new UserProfileEntity();
            profile.setUserId(user.getId());
            userProfileMapper.insert(profile);

            memberService.ensureMember(user.getId());
        } else {
            if ("DISABLED".equals(user.getStatus())) {
                throw BusinessException.of(ResultCode.UNAUTHORIZED, "账号已被禁用, 请联系客服");
            }
            user.setLastLoginAt(LocalDateTime.now());
            // 仅在用户尚未设置昵称时回填授权信息, 避免覆盖用户后续修改
            if (StringUtils.isNotBlank(request.getNickname()) && "优仪会员".equals(user.getNickname())) {
                user.setNickname(request.getNickname());
            }
            if (StringUtils.isNotBlank(request.getAvatarUrl()) && StringUtils.isBlank(user.getAvatarUrl())) {
                user.setAvatarUrl(request.getAvatarUrl());
            }
            userMapper.updateById(user);
        }

        String token = issueToken(user.getId(), "MP_USER");

        if (isNew) {
            // 注册事件: 积分/新人礼包等营销动作由营销模块异步消费(P7)
            MemberEntity member = memberService.byUserId(user.getId());
            eventPublisher.publish(Topics.MEMBER_EVENT, DomainEvent.of(
                    "USER_REGISTERED", user.getOpenid(),
                    Map.of("userId", user.getId(), "memberNo", member != null ? member.getMemberNo() : "")));
        }

        TokenResponse resp = new TokenResponse();
        resp.setToken(token);
        resp.setUserType("MP_USER");
        resp.setNew(isNew);
        resp.setUserId(user.getId());
        resp.setNickname(user.getNickname());
        resp.setAvatarUrl(user.getAvatarUrl());
        resp.setPhone(user.getPhone());
        // 微信绑定教练: 返回 COACH 角色 + coachId, 小程序据此显示教练工作台入口
        CoachBinding binding = coachBindingProvider.getIfAvailable();
        Long coachId = binding != null ? binding.findCoachIdByOpenid(user.getOpenid()) : null;
        if (coachId != null) {
            resp.setRoles(java.util.Set.of("MEMBER", "COACH"));
            resp.setCoachId(coachId);
        } else {
            resp.setRoles(java.util.Set.of("MEMBER"));
        }
        return resp;
    }

    /**
     * 后台账号登录(管理后台 + 小程序教练端).
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenResponse adminLogin(String username, String password) {
        String clientIp=protection.clientIp();
        protection.before(username,clientIp);
        if(username.length()>32 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72){
            protection.failure(username,clientIp);
            throw BusinessException.of(ResultCode.UNAUTHORIZED,"用户名或密码错误，请稍后重试");
        }
        SysUserEntity admin = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getUsername, username));
        boolean matches=passwordEncoder.matches(password,admin==null?dummyHash:admin.getPassword());
        if(admin==null||!matches||!"ACTIVE".equals(admin.getStatus())){
            protection.failure(username,clientIp);
            throw BusinessException.of(ResultCode.UNAUTHORIZED,"用户名或密码错误，请稍后重试");
        }
        if(sysUserMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId,admin.getId()).eq(SysUserEntity::getAuthVersion,admin.getAuthVersion())
                .eq(SysUserEntity::getStatus,"ACTIVE").set(SysUserEntity::getLastLoginAt,LocalDateTime.now()))!=1)
            throw BusinessException.of(ResultCode.UNAUTHORIZED,"用户名或密码错误，请稍后重试");
        protection.success(username);

        String token=jwtService.createToken(admin.getId(),"ADMIN",admin.getAuthVersion());
        redisTemplate.opsForValue().set(RedisKeys.loginToken(token),admin.getId().toString(),Duration.ofMillis(jwtService.ttlOf("ADMIN")));

        TokenResponse resp = new TokenResponse();
        resp.setToken(token);
        resp.setUserType("ADMIN");
        resp.setUserId(admin.getId());
        resp.setName(admin.getName());
        resp.setAvatarUrl(admin.getAvatar());
        resp.setRoles(rbacService.roleCodesOf(admin.getId()));
        resp.setPermissions(rbacService.permCodesOf(admin.getId()));
        CoachBinding binding = coachBindingProvider.getIfAvailable();
        if (binding != null) {
            resp.setCoachId(binding.findCoachIdBySysUserId(admin.getId()));
        }
        return resp;
    }

    /** 登出: 删除 Redis 登录标记, JWT 即刻失效 */
    public void logout(String token) {
        if (StringUtils.isNotBlank(token)) {
            redisTemplate.delete(RedisKeys.loginToken(token));
        }
    }

    private String issueToken(Long userId, String type) {
        String token = jwtService.createToken(userId, type);
        redisTemplate.opsForValue().set(RedisKeys.loginToken(token), String.valueOf(userId),
                Duration.ofMillis(jwtService.ttlOf(type)));
        return token;
    }
}
