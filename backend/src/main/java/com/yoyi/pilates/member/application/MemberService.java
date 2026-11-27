package com.yoyi.pilates.member.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.MaskUtils;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.entity.MemberLevelEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberLevelMapper;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 会员服务: 新用户首次登录自动建档.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final UserMapper users;

    public record BookingMember(Long userId, String nickname, String maskedPhone) {}

    /**
     * 确保会员档案存在(首次登录创建, 默认普通会员).
     *
     * @return 是否新建
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean ensureMember(Long userId) {
        MemberEntity existing = memberMapper.selectOne(
                new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getUserId, userId));
        if (existing != null) {
            return false;
        }
        MemberLevelEntity normal = memberLevelMapper.selectOne(
                new LambdaQueryWrapper<MemberLevelEntity>().eq(MemberLevelEntity::getCode, "NORMAL"));
        MemberEntity member = new MemberEntity();
        member.setMemberNo(NoGenerator.memberNo());
        member.setUserId(userId);
        member.setLevelId(normal != null ? normal.getId() : null);
        member.setTotalSessions(0);
        member.setTotalConsumption(BigDecimal.ZERO);
        member.setStatus("ACTIVE");
        memberMapper.insert(member);
        return true;
    }

    public MemberEntity byUserId(Long userId) {
        return memberMapper.selectOne(
                new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getUserId, userId));
    }

    /** Coach appointment lookup accepts an exact reference and returns only the confirmation fields. */
    public BookingMember findForBooking(String reference) {
        String value = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        boolean phone = value.matches("1[0-9]{10}");
        if (!phone && !value.matches("MB[0-9]{1,30}")) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请输入完整手机号或会员编号");
        }
        var matches = memberMapper.findForBooking(value, phone);
        if (matches.size() > 1) throw BusinessException.of(ResultCode.CONFLICT, "该手机号对应多个会员，请使用会员编号");
        if (matches.isEmpty()) throw BusinessException.of(ResultCode.NOT_FOUND, "未找到可预约的会员，请核对信息");
        var user = users.selectById(matches.get(0).getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "未找到可预约的会员，请核对信息");
        }
        String nickname = user.getNickname();
        String maskedPhone = user.getPhone() != null && user.getPhone().length() >= 7 ? MaskUtils.maskPhone(user.getPhone()) : "";
        return new BookingMember(user.getId(), nickname == null || nickname.isBlank() ? "会员" : nickname, maskedPhone);
    }

    public MemberLevelEntity levelOf(Long levelId) {
        return levelId == null ? null : memberLevelMapper.selectById(levelId);
    }
}
