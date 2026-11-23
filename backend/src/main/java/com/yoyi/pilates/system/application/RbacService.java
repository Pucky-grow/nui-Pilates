package com.yoyi.pilates.system.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.system.infrastructure.entity.SysPermissionEntity;
import com.yoyi.pilates.system.infrastructure.entity.SysRoleEntity;
import com.yoyi.pilates.system.infrastructure.entity.SysRolePermissionEntity;
import com.yoyi.pilates.system.infrastructure.entity.SysUserRoleEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SysPermissionMapper;
import com.yoyi.pilates.system.infrastructure.mapper.SysRoleMapper;
import com.yoyi.pilates.system.infrastructure.mapper.SysRolePermissionMapper;
import com.yoyi.pilates.system.infrastructure.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RBAC 授权每次读取当前数据库状态，撤权/停用角色无需等待缓存 TTL。
 */
@Service
@RequiredArgsConstructor
public class RbacService {

    private static final String ROLE_CACHE = "pilates:rbac:roles:";
    private static final String PERM_CACHE = "pilates:rbac:perms:";

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final StringRedisTemplate redisTemplate;

    /** 后台用户的角色编码集合 */
    public Set<String> roleCodesOf(Long sysUserId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRoleEntity>().eq(SysUserRoleEntity::getUserId, sysUserId))
                .stream().map(SysUserRoleEntity::getRoleId).toList();
        Set<String> codes = roleIds.isEmpty() ? Collections.emptySet()
                : new HashSet<>(roleMapper.selectBatchIds(roleIds).stream()
                        .filter(r -> "ACTIVE".equals(r.getStatus()))
                        .map(SysRoleEntity::getCode).toList());
        return codes;
    }

    /** 后台用户的权限编码集合(经角色聚合) */
    public Set<String> permCodesOf(Long sysUserId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRoleEntity>().eq(SysUserRoleEntity::getUserId, sysUserId))
                .stream().map(SysUserRoleEntity::getRoleId).toList();
        roleIds = roleIds.isEmpty() ? java.util.List.of() : roleMapper.selectBatchIds(roleIds).stream()
                .filter(r -> "ACTIVE".equals(r.getStatus())).map(SysRoleEntity::getId).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> permIds = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermissionEntity>().in(SysRolePermissionEntity::getRoleId, roleIds))
                .stream().map(SysRolePermissionEntity::getPermissionId).distinct().toList();
        Set<String> codes = permIds.isEmpty() ? Collections.emptySet()
                : new HashSet<>(permissionMapper.selectBatchIds(permIds).stream()
                        .map(SysPermissionEntity::getCode).toList());
        return codes;
    }

    /** 清理旧版本遗留的展示缓存；当前授权不读取这些缓存。 */
    public void evictCache(Long sysUserId) {
        redisTemplate.delete(List.of(ROLE_CACHE + sysUserId, PERM_CACHE + sysUserId));
    }
}
