package com.yoyi.pilates.system.security;

import lombok.Data;

import java.io.Serializable;
import java.util.Set;

/**
 * 登录主体(存入 SecurityContext).
 *
 * <p>type=MP_USER 小程序用户(含教练角色); type=ADMIN 后台用户.</p>
 */
@Data
public class LoginUser implements Serializable {

    /** 用户ID(小程序 user.id 或后台 sys_user.id) */
    private Long id;
    private Long authVersion;

    /** MP_USER / ADMIN */
    private String type;

    /** 昵称或姓名(日志展示用) */
    private String name;

    /** 角色编码集合, 如 ADMIN / COACH / MEMBER */
    private Set<String> roles;

    /** 权限编码集合, 如 course:view */
    private Set<String> permissions;

    /** 若是教练, 关联 coach.id(数据权限: 仅本人课程与学员) */
    private Long coachId;

    public boolean isAdmin() {
        return "ADMIN".equals(type);
    }

    public boolean isCoach() {
        return roles != null && roles.contains("COACH");
    }

    /** 管理员角色(拥有全部权限) */
    public boolean isAdminRole() {
        return roles != null && roles.contains("ADMIN");
    }

    /** @deprecated 角色收敛后由 {@link #isAdminRole()} 取代 */
    @Deprecated
    public boolean isSuperAdmin() {
        return isAdminRole();
    }

    public boolean hasPerm(String perm) {
        return isAdminRole() || (permissions != null && permissions.contains(perm));
    }
}
