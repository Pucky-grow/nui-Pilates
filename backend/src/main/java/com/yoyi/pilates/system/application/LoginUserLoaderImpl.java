package com.yoyi.pilates.system.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.system.infrastructure.entity.SysUserEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SysUserMapper;
import com.yoyi.pilates.system.security.CoachBinding;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.LoginUserLoader;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * {@link LoginUserLoader} 实现: JWT 过滤器每次请求经此加载角色/权限.
 */
@Component
@RequiredArgsConstructor
public class LoginUserLoaderImpl implements LoginUserLoader {

    private final UserMapper userMapper;
    private final SysUserMapper sysUserMapper;
    private final RbacService rbacService;
    private final ObjectProvider<CoachBinding> coachBindingProvider;

    @Override
    public Optional<LoginUser> load(Long userId, String type) {
        return switch (type) { case "ADMIN" -> loadAdmin(userId); case "MP_USER" -> loadMpUser(userId); default -> Optional.empty(); };
    }

    private Optional<LoginUser> loadMpUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getOpenid() == null || "DISABLED".equals(user.getStatus())) {
            return Optional.empty();
        }
        // 小程序用户固定 MEMBER 角色; 若其微信已绑定为某教练, 追加 COACH 角色 + coachId
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setType("MP_USER");
        loginUser.setName(user.getNickname());
        CoachBinding binding = coachBindingProvider.getIfAvailable();
        Long coachId = binding != null ? binding.findCoachIdByOpenid(user.getOpenid()) : null;
        if (coachId != null) {
            loginUser.setRoles(Set.of("MEMBER", "COACH"));
            loginUser.setCoachId(coachId);
        } else {
            loginUser.setRoles(Set.of("MEMBER"));
        }
        Set<String> permissions = Set.of();
        Long sysId = binding != null && coachId != null ? binding.findSysUserIdByCoachId(coachId) : null;
        SysUserEntity account = sysId != null ? sysUserMapper.selectById(sysId) : null;
        if (account != null && "ACTIVE".equals(account.getStatus()) && rbacService.roleCodesOf(sysId).contains("COACH")) {
            permissions = rbacService.permCodesOf(sysId).stream()
                    .filter(p -> p.startsWith("coach:") && !p.startsWith("coach:home:"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        loginUser.setPermissions(permissions);
        return Optional.of(loginUser);
    }

    private Optional<LoginUser> loadAdmin(Long sysUserId) {
        SysUserEntity admin = sysUserMapper.selectById(sysUserId);
        if (admin == null || !"ACTIVE".equals(admin.getStatus())) {
            return Optional.empty();
        }
        LoginUser loginUser = new LoginUser();
        loginUser.setId(admin.getId());
        loginUser.setType("ADMIN");
        loginUser.setAuthVersion(admin.getAuthVersion()==null?0L:admin.getAuthVersion());
        loginUser.setName(admin.getName());
        loginUser.setRoles(rbacService.roleCodesOf(sysUserId));
        loginUser.setPermissions(rbacService.permCodesOf(sysUserId));
        CoachBinding binding = coachBindingProvider.getIfAvailable();
        if (binding != null) {
            loginUser.setCoachId(binding.findCoachIdBySysUserId(sysUserId));
        }
        return Optional.of(loginUser);
    }
}
