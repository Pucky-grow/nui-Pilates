package com.yoyi.pilates.system.security;

import java.util.Optional;

/**
 * 登录用户加载器: 由用户/RBAC 模块实现(P4), 过滤器仅依赖该接口.
 * 按 (userId, type) 加载角色与权限.
 */
public interface LoginUserLoader {

    /**
     * @param userId user.id(MP_USER) 或 sys_user.id(ADMIN)
     * @param type   MP_USER / ADMIN
     * @return 完整 LoginUser; 用户不存在或被禁用返回 empty
     */
    Optional<LoginUser> load(Long userId, String type);
}
