package com.yoyi.pilates.coach.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.MaskUtils;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.entity.MemberLevelEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberLevelMapper;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.training.infrastructure.entity.BodyAssessmentEntity;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import com.yoyi.pilates.training.infrastructure.mapper.BodyAssessmentMapper;
import com.yoyi.pilates.training.infrastructure.mapper.TrainingRecordMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.entity.UserPrivacyEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserPrivacyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教练学员档案服务(严格数据权限 + 隐私授权).
 *
 * <p>教练只能查看自己服务过的学员; 身体评估/历史训练受 user_privacy 授权约束.</p>
 */
@Service
@RequiredArgsConstructor
public class CoachStudentService {

    private final UserMapper userMapper;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final TrainingRecordMapper trainingRecordMapper;
    private final BodyAssessmentMapper bodyAssessmentMapper;
    private final BookingMapper bookingMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final UserPrivacyMapper privacyMapper;

    /**
     * 学员档案详情.
     */
    public Map<String, Object> studentDetail(Long coachId, Long userId) {
        // 数据权限: 该学员是否有本教练授课记录
        boolean served = hasServed(coachId, userId);
        if (!served) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能查看自己服务过的学员");
        }

        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getOpenid() == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "学员不存在");
        }

        Map<String, Object> view = new HashMap<>();
        view.put("userId", user.getId());
        view.put("nickname", user.getNickname());
        view.put("avatarUrl", user.getAvatarUrl());
        view.put("phone", MaskUtils.maskPhone(user.getPhone()));
        view.put("gender", user.getGender());

        MemberEntity member = memberMapper.selectOne(
                new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getUserId, userId));
        if (member != null) {
            view.put("memberNo", member.getMemberNo());
            view.put("totalSessions", member.getTotalSessions());
            view.put("lastTrainingAt", member.getLastTrainingAt());
            MemberLevelEntity level = memberLevelMapper.selectById(member.getLevelId());
            view.put("levelName", level != null ? level.getName() : "普通会员");
        }

        // 隐私设置
        UserPrivacyEntity privacy = privacyMapper.selectOne(
                new LambdaQueryWrapper<UserPrivacyEntity>().eq(UserPrivacyEntity::getUserId, userId));

        // 历史训练(本教练填写)
        List<TrainingRecordEntity> myTraining = trainingRecordMapper.selectList(
                new LambdaQueryWrapper<TrainingRecordEntity>()
                        .eq(TrainingRecordEntity::getCoachId, coachId)
                        .eq(TrainingRecordEntity::getUserId, userId)
                        .orderByDesc(TrainingRecordEntity::getTrainingDate));
        boolean allowTraining = privacy == null || privacy.getAllowTrainingView() == 1;
        view.put("trainingHistory", allowTraining ? myTraining : List.of());
        view.put("allowTrainingView", allowTraining);

        // 身体评估(受授权约束)
        boolean allowAssessment = privacy == null || privacy.getAllowAssessmentView() == 1;
        if (allowAssessment) {
            List<BodyAssessmentEntity> assessments = bodyAssessmentMapper.selectList(
                    new LambdaQueryWrapper<BodyAssessmentEntity>()
                            .eq(BodyAssessmentEntity::getUserId, userId)
                            .orderByDesc(BodyAssessmentEntity::getAssessDate));
            view.put("assessments", assessments);
        } else {
            view.put("assessments", List.of());
        }
        view.put("allowAssessmentView", allowAssessment);

        return view;
    }

    /** 判断该学员是否有本教练授课记录(预约或训练记录), 供各教练端接口做数据范围校验 */
    public boolean hasServed(Long coachId, Long userId) {
        if (coachId == null || userId == null) return false;
        // 本教练授课的排课中是否有该学员的预约
        List<Long> myScheduleIds = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getCoachId, coachId)
                        .ne(CourseScheduleEntity::getStatus, "CANCELLED"))
                .stream().map(CourseScheduleEntity::getId).toList();
        if (myScheduleIds.isEmpty()) {
            return false;
        }
        return bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getUserId, userId)
                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED"))
                .in(BookingEntity::getScheduleId, myScheduleIds)) > 0;
    }
    public void requireAccess(Long coachId, Long userId, boolean assessment) {
        if (!hasServed(coachId, userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能访问本人真实授课关系的学员");
        }
        UserPrivacyEntity privacy = privacyMapper.selectOne(new LambdaQueryWrapper<UserPrivacyEntity>()
                .eq(UserPrivacyEntity::getUserId, userId));
        if (privacy != null && !Integer.valueOf(1).equals(assessment
                ? privacy.getAllowAssessmentView() : privacy.getAllowTrainingView())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "会员已关闭" + (assessment ? "身体评估" : "训练记录") + "查看授权");
        }
    }

}
