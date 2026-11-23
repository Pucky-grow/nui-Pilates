package com.yoyi.pilates.system.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.infrastructure.entity.*;
import com.yoyi.pilates.system.infrastructure.mapper.*;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service @RequiredArgsConstructor
public class AccountService {
    private final SysUserMapper users;private final SysRoleMapper roles;private final SysUserRoleMapper links;
    private final PasswordEncoder encoder;private final JdbcTemplate jdbc;
    public static void validatePassword(String value){
        if(value==null||value.length()<14||value.getBytes(StandardCharsets.UTF_8).length>72||value.isBlank())
            throw BusinessException.of(ResultCode.BAD_REQUEST,"新口令须为14至72字节，避免使用默认口令");
    }
    @Transactional public Long create(String username,String name,String password,String role,Long coachId){
        requireAdmin();validatePassword(password);
        if(username==null||!username.matches("[A-Za-z0-9_.-]{3,32}")||name==null||name.isBlank()||name.length()>32)throw BusinessException.of(ResultCode.BAD_REQUEST,"账号或姓名格式无效");
        if(!Set.of("STORE_MANAGER","STAFF","COACH").contains(role))throw BusinessException.of(ResultCode.BAD_REQUEST,"只能创建店长、前台或教练账号");
        if(users.exists(new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getUsername,username)))throw BusinessException.of(ResultCode.CONFLICT,"账号不可用");
        var r=roles.selectOne(new LambdaQueryWrapper<SysRoleEntity>().eq(SysRoleEntity::getCode,role).eq(SysRoleEntity::getStatus,"ACTIVE"));
        if(r==null)throw BusinessException.of(ResultCode.CONFLICT,"角色未配置");
        if(role.equals("COACH")&&(coachId==null||jdbc.queryForObject("SELECT COUNT(*) FROM coach WHERE id=? AND deleted=0 AND sys_user_id IS NULL AND status='ACTIVE'",Integer.class,coachId)!=1))
            throw BusinessException.of(ResultCode.BAD_REQUEST,"请选择尚未绑定账号的启用教练档案");
        var u=new SysUserEntity();u.setUsername(username);u.setName(name);u.setPassword(encoder.encode(password));u.setAuthVersion(0L);u.setStatus("ACTIVE");users.insert(u);
        var link=new SysUserRoleEntity();link.setUserId(u.getId());link.setRoleId(r.getId());links.insert(link);
        if(role.equals("COACH")&&jdbc.update("UPDATE coach SET sys_user_id=? WHERE id=? AND deleted=0 AND sys_user_id IS NULL",u.getId(),coachId)!=1)throw BusinessException.of(ResultCode.CONFLICT);
        return u.getId();
    }
    @Transactional public void changeOwnPassword(String oldPassword,String newPassword){
        var actor=SecurityUtils.requireUser();if(!actor.isAdmin())throw BusinessException.of(ResultCode.FORBIDDEN);
        var user=users.selectById(actor.getId());if(user==null||!encoder.matches(oldPassword,user.getPassword()))throw BusinessException.of(ResultCode.UNAUTHORIZED,"当前口令校验失败");
        validatePassword(newPassword);if(encoder.matches(newPassword,user.getPassword()))throw BusinessException.of(ResultCode.BAD_REQUEST,"新口令不能与旧口令相同");
        jdbc.update("UPDATE sys_user SET password=?,auth_version=auth_version+1 WHERE id=? AND deleted=0",encoder.encode(newPassword),user.getId());
    }
    @Transactional public void reset(Long id,String password){requireAdmin();validatePassword(password);if(users.selectById(id)==null)throw BusinessException.of(ResultCode.NOT_FOUND);jdbc.update("UPDATE sys_user SET password=?,auth_version=auth_version+1 WHERE id=? AND deleted=0",encoder.encode(password),id);}
    @Transactional public void status(Long id,String status){
        requireAdmin();if(status==null||!Set.of("ACTIVE","DISABLED").contains(status))throw BusinessException.of(ResultCode.BAD_REQUEST);
        if(id.equals(SecurityUtils.requireUser().getId()))throw BusinessException.of(ResultCode.CONFLICT,"不能在此停用本人账号");
        jdbc.queryForList("SELECT id FROM sys_user WHERE deleted=0 ORDER BY id FOR UPDATE");
        if(users.selectById(id)==null)throw BusinessException.of(ResultCode.NOT_FOUND);
        if(status.equals("DISABLED")&&jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id AND ur.deleted=0 JOIN sys_role r ON r.id=ur.role_id AND r.deleted=0 WHERE u.id<>? AND u.status='ACTIVE' AND u.deleted=0 AND r.code='ADMIN' AND r.status='ACTIVE'",Integer.class,id)<1)
            throw BusinessException.of(ResultCode.CONFLICT,"必须保留启用管理员");
        jdbc.update("UPDATE sys_user SET status=?,auth_version=auth_version+1 WHERE id=? AND deleted=0",status,id);
    }
    public void requireAdmin(){if(!SecurityUtils.requireUser().isAdminRole())throw BusinessException.of(ResultCode.FORBIDDEN);}
}
