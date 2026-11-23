package com.yoyi.pilates.user.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.MaskUtils;
import com.yoyi.pilates.member.application.MemberService;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.entity.MemberLevelEntity;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.entity.UserProfileEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserProfileMapper;
import com.yoyi.pilates.user.interfaces.dto.ProfileUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户资料服务.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserProfileMapper userProfileMapper;
    private final MemberService memberService;

    /** 个人中心聚合视图(手机号脱敏) */
    public Map<String, Object> myProfile(Long userId) {
        UserEntity user = requireUser(userId);
        UserProfileEntity profile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfileEntity>().eq(UserProfileEntity::getUserId, userId));
        MemberEntity member = memberService.byUserId(userId);
        MemberLevelEntity level = member != null ? memberService.levelOf(member.getLevelId()) : null;

        Map<String, Object> view = new HashMap<>();
        view.put("userId", user.getId());
        view.put("nickname", user.getNickname());
        view.put("avatarUrl", user.getAvatarUrl());
        view.put("gender", user.getGender());
        view.put("phone", MaskUtils.maskPhone(user.getPhone()));
        view.put("phoneBound", StringUtils.isNotBlank(user.getPhone()));
        if (profile != null) {
            view.put("realName", profile.getRealName());
            view.put("birthday", profile.getBirthday());
            view.put("height", profile.getHeight());
            view.put("weight", profile.getWeight());
        }
        if (member != null) {
            view.put("memberNo", member.getMemberNo());
            view.put("validUntil", member.getValidUntil());
            view.put("totalSessions", member.getTotalSessions());
            view.put("totalConsumption", member.getTotalConsumption());
            view.put("lastTrainingAt", member.getLastTrainingAt());
        }
        view.put("levelName", level != null ? level.getName() : "普通会员");
        view.put("levelCode", level != null ? level.getCode() : "NORMAL");
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long userId, ProfileUpdateRequest request) {
        UserEntity user = requireUser(userId);
        if (StringUtils.isNotBlank(request.getNickname())) {
            user.setNickname(request.getNickname());
        }
        if (StringUtils.isNotBlank(request.getAvatarUrl())) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        userMapper.updateById(user);

        UserProfileEntity profile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfileEntity>().eq(UserProfileEntity::getUserId, userId));
        if (profile == null) {
            profile = new UserProfileEntity();
            profile.setUserId(userId);
            userProfileMapper.insert(profile);
        }
        if (StringUtils.isNotBlank(request.getRealName())) {
            profile.setRealName(request.getRealName());
        }
        if (request.getBirthday() != null) {
            profile.setBirthday(request.getBirthday());
        }
        if (request.getHeight() != null) {
            profile.setHeight(request.getHeight());
        }
        if (request.getWeight() != null) {
            profile.setWeight(request.getWeight());
        }
        userProfileMapper.updateById(profile);
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindPhone(Long userId, String phone) {
        if (StringUtils.isBlank(phone)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "手机号不能为空");
        }
        if (!phone.matches("^1\\d{10}$")) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "手机号格式不正确");
        }
        UserEntity user = requireUser(userId);
        user.setPhone(phone);
        userMapper.updateById(user);
    }

    public UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
