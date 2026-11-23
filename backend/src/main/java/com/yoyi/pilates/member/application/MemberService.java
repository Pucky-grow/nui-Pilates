package com.yoyi.pilates.member.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.entity.MemberLevelEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberLevelMapper;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 会员服务: 新用户首次登录自动建档.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;

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

    public MemberLevelEntity levelOf(Long levelId) {
        return levelId == null ? null : memberLevelMapper.selectById(levelId);
    }
}
