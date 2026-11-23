package com.yoyi.pilates.system.interfaces;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.result.*;
import com.yoyi.pilates.system.application.*;
import com.yoyi.pilates.system.infrastructure.entity.SysUserEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SysUserMapper;
import com.yoyi.pilates.system.security.RequiresPerm;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor
public class AccountController {
    private final AccountService service;private final SysUserMapper users;private final RbacService rbac;
    public record Create(@NotBlank String username,@NotBlank String name,@NotBlank String password,@NotBlank String role,Long coachId){}
    public record Password(@NotBlank String password){}
    public record Change(@NotBlank String oldPassword,@NotBlank String newPassword){}
    @GetMapping("/api/admin/accounts") @RequiresPerm("system:manage")
    public Result<PageResult<Map<String,Object>>> list(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size){
        service.requireAdmin();if(page<1||size<1||size>100)throw com.yoyi.pilates.common.exception.BusinessException.of(ResultCode.BAD_REQUEST);
        return Result.ok(PageResult.of(users.selectPage(Page.of(page,size),new LambdaQueryWrapper<SysUserEntity>().orderByAsc(SysUserEntity::getId)),u->{
            Map<String,Object> v=new LinkedHashMap<>();v.put("id",u.getId());v.put("username",u.getUsername());v.put("name",u.getName());v.put("status",u.getStatus());v.put("roles",rbac.roleCodesOf(u.getId()));v.put("lastLoginAt",u.getLastLoginAt());return v;}));
    }
    @PostMapping("/api/admin/accounts") @RequiresPerm("system:manage") @OpLog(module="account",action="create",detail="'创建后台账号（口令不记录）'")
    public Result<Long> create(@Valid @RequestBody Create r){return Result.ok(service.create(r.username(),r.name(),r.password(),r.role(),r.coachId()));}
    @PostMapping("/api/admin/accounts/{id}/password") @RequiresPerm("system:manage") @OpLog(module="account",action="reset_password",targetId="#id",detail="'重置凭据并撤销旧会话'")
    public Result<Void> reset(@PathVariable Long id,@Valid @RequestBody Password r){service.reset(id,r.password());return Result.ok();}
    @PutMapping("/api/admin/accounts/{id}/status") @RequiresPerm("system:manage") @OpLog(module="account",action="status",targetId="#id",detail="#r.get('status')")
    public Result<Void> status(@PathVariable Long id,@RequestBody Map<String,String> r){service.status(id,r.getOrDefault("status",""));return Result.ok();}
    @PostMapping("/api/auth/password") @OpLog(module="account",action="change_password",detail="'修改本人凭据并撤销旧会话'")
    public Result<Void> change(@Valid @RequestBody Change r){service.changeOwnPassword(r.oldPassword(),r.newPassword());return Result.ok();}
}
