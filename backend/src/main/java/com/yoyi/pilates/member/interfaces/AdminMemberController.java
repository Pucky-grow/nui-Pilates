package com.yoyi.pilates.member.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.infra.storage.FileService;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.entity.MemberLevelEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberLevelMapper;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.system.security.RequiresPerm;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 会员管理接口(后台): 查询/编辑/调整课时/等级/有效期.
 */
@RestController
@RequestMapping({"/admin/members", "/api/admin/members"})
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final UserMapper userMapper;
    private final PackageService packageService;
    private final FileService files;
    private final com.yoyi.pilates.system.infrastructure.mapper.OperationLogMapper operationLogs;
    private String snapshot(MemberEntity m,UserEntity u){return "nickname="+u.getNickname()+", phone="+com.yoyi.pilates.common.util.MaskUtils.maskPhone(u.getPhone())+", gender="+u.getGender()+", level="+m.getLevelId()+", validUntil="+m.getValidUntil()+", status="+m.getStatus();}

    @GetMapping
    @RequiresPerm("member:view")
    public Result<PageResult<Map<String, Object>>> members(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Long levelId,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "10") long size) {
        if (page < 1 || size < 1 || size > 100) throw BusinessException.of(ResultCode.BAD_REQUEST,"分页范围无效");
        Page<MemberEntity> p = memberMapper.search(Page.of(page, size), keyword, levelId, !studioAdmin());
        Map<Long, String> levelNames = memberLevelMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(MemberLevelEntity::getId, MemberLevelEntity::getName));
        Map<Long, UserEntity> users = (p.getRecords().isEmpty() ? java.util.List.<UserEntity>of() : userMapper.selectBatchIds(
                        p.getRecords().stream().map(MemberEntity::getUserId).toList())).stream()
                .collect(java.util.stream.Collectors.toMap(UserEntity::getId, u -> u));
        return Result.ok(PageResult.of(p, m -> {
            Map<String, Object> v = new HashMap<>();
            v.put("member", visibleMember(m));
            v.put("levelName", levelNames.get(m.getLevelId()));
            UserEntity u = users.get(m.getUserId());
            if (u != null) {
                v.put("nickname", u.getNickname());
                v.put("phone", u.getPhone());
                v.put("avatarUrl", u.getAvatarUrl());
            }
            return v;
        }));
    }

    @GetMapping("/{userId}")
    @RequiresPerm("member:view")
    public Result<Map<String, Object>> detail(@PathVariable Long userId) {
        MemberEntity member = memberMapper.selectOne(
                new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getUserId, userId));
        if (member == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "会员不存在");
        }
        Map<String, Object> v = new HashMap<>();
        v.put("member", visibleMember(member));
        UserEntity user = userMapper.selectById(userId);
        requireVisible(user);
        if (studioAdmin()) v.put("wechatBound", user.getOpenid() != null);
        Map<String,Object> profile = new HashMap<>();
        if(user != null) {profile.put("id",user.getId());profile.put("nickname",user.getNickname());profile.put("phone",user.getPhone());
            profile.put("avatarUrl",user.getAvatarUrl());profile.put("gender",user.getGender());profile.put("status",user.getStatus());}
        v.put("user", profile);
        v.put("levels", memberLevelMapper.selectList(null));
        return Result.ok(v);
    }

    /** 编辑会员: 等级/有效期/状态 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @PutMapping("/{userId}")
    @RequiresPerm("member:update")
    @OpLog(module = "member", action = "update", targetId = "#userId", detail = "'会员资料更新（详见profile_change记录）'")
    public Result<Void> update(@PathVariable Long userId, @RequestBody Map<String, Object> body) {
        MemberEntity member = memberMapper.selectOne(
                new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getUserId, userId));
        if (member == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "会员不存在");
        }
        java.util.Set<String> allowed = java.util.Set.of("levelId","validUntil","status","nickname","phone","gender","avatarUrl","reason");
        if (!allowed.containsAll(body.keySet())) throw BusinessException.of(ResultCode.BAD_REQUEST,"包含不可编辑字段");
        UserEntity user = userMapper.selectById(userId);
        requireVisible(user);
        if(String.valueOf(body.getOrDefault("reason","")).length()>255)throw BusinessException.of(ResultCode.BAD_REQUEST,"操作原因不能超过255字");
        String before=snapshot(member,user);
        if(body.containsKey("nickname")) {
            String name=String.valueOf(body.get("nickname")).trim();
            if(name.isBlank()||name.length()>64) throw BusinessException.of(ResultCode.BAD_REQUEST,"昵称需为1至64字");
            user.setNickname(name);
        }
        if(body.containsKey("phone")) {
            String phone=String.valueOf(body.get("phone")).trim();
            if(!phone.matches("1[3-9][0-9]{9}")) throw BusinessException.of(ResultCode.BAD_REQUEST,"请输入有效手机号");
            if(userMapper.exists(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getPhone,phone).ne(UserEntity::getId,userId)))
                throw BusinessException.of(ResultCode.CONFLICT,"手机号已被其他会员使用");
            user.setPhone(phone);
        }
        if (body.containsKey("avatarUrl")) {
            String avatar = body.get("avatarUrl") instanceof String value ? value : null;
            user.setAvatarUrl(files.memberAvatar(avatar, userId));
        }
        if(body.get("gender")!=null) {
            if(!body.get("gender").toString().matches("[0-2]"))throw BusinessException.of(ResultCode.BAD_REQUEST,"性别无效");
            int gender=Integer.parseInt(body.get("gender").toString());
            if(gender<0||gender>2) throw BusinessException.of(ResultCode.BAD_REQUEST,"性别无效");user.setGender(gender);
        }
        if (body.get("levelId") != null) {
            if(!body.get("levelId").toString().matches("[0-9]{1,18}"))throw BusinessException.of(ResultCode.BAD_REQUEST,"等级无效");
            Long level=Long.valueOf(body.get("levelId").toString());
            if(memberLevelMapper.selectById(level)==null) throw BusinessException.of(ResultCode.BAD_REQUEST,"等级不存在");
            member.setLevelId(level);
        }
        if (body.containsKey("validUntil")) {
            try{member.setValidUntil(body.get("validUntil")==null||body.get("validUntil").toString().isBlank()?null:LocalDate.parse(body.get("validUntil").toString()));}
            catch(java.time.format.DateTimeParseException e){throw BusinessException.of(ResultCode.BAD_REQUEST,"有效期日期无效");}
        }
        if (body.get("status") != null) {
            String status=body.get("status").toString();
            if(!java.util.Set.of("ACTIVE","EXPIRED","DISABLED").contains(status)) throw BusinessException.of(ResultCode.BAD_REQUEST,"会员状态无效");
            member.setStatus(status);
            if("DISABLED".equals(status)) user.setStatus("DISABLED");
            else if("DISABLED".equals(user.getStatus())) user.setStatus("ACTIVE");
        }
        if (body.get("levelId") != null || body.containsKey("validUntil") || body.get("status") != null) {
            // Only profile fields are writable here. A class may finish while this edit is in progress.
            int updated = memberMapper.update(null, new LambdaUpdateWrapper<MemberEntity>()
                    .eq(MemberEntity::getId, member.getId())
                    .set(body.get("levelId") != null, MemberEntity::getLevelId, member.getLevelId())
                    .set(body.containsKey("validUntil"), MemberEntity::getValidUntil, member.getValidUntil())
                    .set(body.get("status") != null, MemberEntity::getStatus, member.getStatus())
                    .set(MemberEntity::getUpdatedAt, com.yoyi.pilates.common.util.StudioTime.now()));
            if (updated != 1) throw BusinessException.of(ResultCode.CONFLICT);
        }
        if (userMapper.updateById(user) != 1) throw BusinessException.of(ResultCode.CONFLICT);
        var audit=new com.yoyi.pilates.system.infrastructure.entity.OperationLogEntity();
        var actor=SecurityUtils.requireUser();audit.setOperatorId(actor.getId());audit.setOperatorName(actor.getType()+":"+actor.getName());
        audit.setModule("member");audit.setAction("profile_change");audit.setTargetId(userId.toString());
        audit.setDetail("before={"+before+"}; after={"+snapshot(member,user)+"}; reason="+String.valueOf(body.getOrDefault("reason","后台编辑")));
        if(operationLogs.insert(audit)!=1)throw BusinessException.of(ResultCode.CONFLICT,"操作记录写入失败，修改未生效");
        return Result.ok();
    }

    private boolean studioAdmin() {
        var actor = SecurityUtils.requireUser();
        return actor.isAdmin() && actor.isAdminRole();
    }

    private void requireVisible(UserEntity user) {
        if (user == null || (user.getOpenid() == null && !studioAdmin())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "会员不存在");
        }
    }

    private MemberEntity visibleMember(MemberEntity member) {
        var actor=SecurityUtils.requireUser();
        if(!actor.isAdminRole() && !actor.getRoles().contains("STORE_MANAGER")) member.setTotalConsumption(null);
        return member;
    }

    /** 调整课时(充值/赠送/扣减, 必记操作日志 + 课时流水) */
    @PostMapping("/packages/{userPackageId}/adjust")
    @RequiresPerm("member:adjust")
    @OpLog(module = "member", action = "adjust_credits", targetId = "#userPackageId",
            detail = "'delta=' + #body.get('delta') + ' remark=' + #body.get('remark')")
    public Result<Void> adjustCredits(@PathVariable Long userPackageId, @RequestBody Map<String, Object> body) {
        int delta = Integer.parseInt(body.get("delta").toString());
        String remark = body.get("remark") == null ? "后台调整" : body.get("remark").toString();
        String operator = SecurityUtils.current().map(u -> u.getName()).orElse("admin");
        packageService.adjust(userPackageId, delta, operator, remark);
        return Result.ok();
    }
}
