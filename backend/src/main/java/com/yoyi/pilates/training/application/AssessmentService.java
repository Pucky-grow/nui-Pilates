package com.yoyi.pilates.training.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.training.infrastructure.entity.BodyAssessmentEntity;
import com.yoyi.pilates.training.infrastructure.entity.BodyMeasurementEntity;
import com.yoyi.pilates.training.infrastructure.mapper.BodyAssessmentMapper;
import com.yoyi.pilates.training.infrastructure.mapper.BodyMeasurementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 身体评估与数据追踪服务.
 */
@Service
@RequiredArgsConstructor
public class AssessmentService {

    private final BodyAssessmentMapper assessmentMapper;
    private final BodyMeasurementMapper measurementMapper;

    /** 教练为会员创建身体评估 */
    @Transactional(rollbackFor = Exception.class)
    public Long createAssessment(Long userId, Long coachId, BodyAssessmentEntity assessment) {
        assessment.setUserId(userId);
        assessment.setCoachId(coachId);
        if (assessment.getAssessDate() == null) {
            assessment.setAssessDate(LocalDate.now());
        }
        assessmentMapper.insert(assessment);
        // 同步一条围度追踪记录(趋势图)
        BodyMeasurementEntity m = new BodyMeasurementEntity();
        m.setUserId(userId);
        m.setMeasureDate(assessment.getAssessDate());
        m.setWeight(assessment.getWeight());
        m.setBodyFatRate(assessment.getBodyFatRate());
        m.setWaist(assessment.getWaist());
        m.setHip(assessment.getHip());
        m.setThigh(assessment.getThigh());
        m.setRemark("评估同步");
        measurementMapper.insert(m);
        return assessment.getId();
    }

    public List<BodyAssessmentEntity> myAssessments(Long userId) {
        return assessmentMapper.selectList(new LambdaQueryWrapper<BodyAssessmentEntity>()
                .eq(BodyAssessmentEntity::getUserId, userId)
                .orderByDesc(BodyAssessmentEntity::getAssessDate));
    }

    public BodyAssessmentEntity assessmentDetail(Long userId, Long id) {
        BodyAssessmentEntity assessment = assessmentMapper.selectById(id);
        if (assessment == null || !assessment.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "评估记录不存在");
        }
        return assessment;
    }

    /** 围度/体重趋势(图表) */
    public List<BodyMeasurementEntity> measurementTrend(Long userId) {
        return measurementMapper.selectList(new LambdaQueryWrapper<BodyMeasurementEntity>()
                .eq(BodyMeasurementEntity::getUserId, userId)
                .orderByAsc(BodyMeasurementEntity::getMeasureDate)
                .last("LIMIT 60"));
    }

    /** 用户自助记录一次身体数据 */
    @Transactional(rollbackFor = Exception.class)
    public Long recordMeasurement(Long userId, BodyMeasurementEntity measurement) {
        measurement.setUserId(userId);
        if (measurement.getMeasureDate() == null) {
            measurement.setMeasureDate(LocalDate.now());
        }
        measurementMapper.insert(measurement);
        return measurement.getId();
    }
}
