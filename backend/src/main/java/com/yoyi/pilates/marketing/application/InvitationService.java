package com.yoyi.pilates.marketing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.marketing.infrastructure.entity.InvitationEntity;
import com.yoyi.pilates.marketing.infrastructure.mapper.InvitationMapper;
import com.yoyi.pilates.system.application.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 邀请好友服务: 生成邀请码 / 被邀请人绑定 / 首次购买发奖励(幂等).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final InvitationMapper invitationMapper;
    private final PointsService pointsService;
    private final SystemConfigService configService;

    /** 获取或创建我的邀请码 */
    @Transactional(rollbackFor = Exception.class)
    public InvitationEntity myCode(Long inviterId) {
        InvitationEntity existing = invitationMapper.selectOne(
                new LambdaQueryWrapper<InvitationEntity>()
                        .eq(InvitationEntity::getInviterId, inviterId)
                        .orderByDesc(InvitationEntity::getId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        InvitationEntity invitation = new InvitationEntity();
        invitation.setInviterId(inviterId);
        invitation.setInviteCode(NoGenerator.next("IV"));
        invitation.setStatus("PENDING");
        invitationMapper.insert(invitation);
        return invitation;
    }

    /** 被邀请人绑定(注册时使用邀请码), 防自己邀请自己/重复绑定 */
    @Transactional(rollbackFor = Exception.class)
    public void bindInvitee(Long inviteeId, String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            return;
        }
        InvitationEntity invitation = invitationMapper.selectOne(
                new LambdaQueryWrapper<InvitationEntity>()
                        .eq(InvitationEntity::getInviteCode, inviteCode));
        if (invitation == null) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "邀请码无效");
        }
        if (invitation.getInviterId().equals(inviteeId)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "不能邀请自己");
        }
        if (invitation.getInviteeId() != null) {
            throw BusinessException.of(ResultCode.CONFLICT, "邀请码已被使用");
        }
        invitation.setInviteeId(inviteeId);
        invitation.setStatus("REGISTERED");
        invitationMapper.updateById(invitation);
    }

    /**
     * 被邀请人首次购买后给邀请人发积分(幂等: 仅 REGISTERED → REWARDED 触发一次).
     */
    @Transactional(rollbackFor = Exception.class)
    public void rewardOnOrderPaid(Long inviteeId) {
        InvitationEntity invitation = invitationMapper.selectOne(
                new LambdaQueryWrapper<InvitationEntity>()
                        .eq(InvitationEntity::getInviteeId, inviteeId)
                        .eq(InvitationEntity::getStatus, "REGISTERED")
                        .last("LIMIT 1"));
        if (invitation == null) {
            return;
        }
        int points = configService.getInt("points.invite", 100);
        pointsService.earn(invitation.getInviterId(), points, "邀请好友",
                "INVITE", String.valueOf(invitation.getId()));
        invitation.setStatus("REWARDED");
        invitation.setRewardedAt(LocalDateTime.now());
        invitationMapper.updateById(invitation);
        log.info("邀请奖励发放: inviter={} invitee={} points={}",
                invitation.getInviterId(), inviteeId, points);
    }
}
