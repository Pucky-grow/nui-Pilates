package com.yoyi.pilates.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 课程-授课教练服务: 教练申请授课资格 / 我的可授课课程 / 管理员审核.
 */
@Service
@RequiredArgsConstructor
public class CourseCoachService {

    private final com.yoyi.pilates.booking.application.ScheduleExecution execution;
    private final com.yoyi.pilates.course.infrastructure.mapper.CoachMapper coachMapper;
    private final CourseCoachMapper courseCoachMapper;
    private final CourseMapper courseMapper;

    /** 我(教练)已获授课资格/审核中的课程 */
    public List<Map<String, Object>> myCourses(Long coachId) {
        List<CourseCoachEntity> relations = courseCoachMapper.selectList(
                new LambdaQueryWrapper<CourseCoachEntity>()
                        .eq(CourseCoachEntity::getCoachId, coachId)
                        .orderByDesc(CourseCoachEntity::getId));
        if (relations.isEmpty()) {
            return List.of();
        }
        Map<Long, CourseEntity> courseMap = courseMapper.selectBatchIds(
                        relations.stream().map(CourseCoachEntity::getCourseId).toList()).stream()
                .collect(Collectors.toMap(CourseEntity::getId, c -> c));
        return relations.stream().map(r -> {
            CourseEntity c = courseMap.get(r.getCourseId());
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("relationId", r.getId());
            m.put("courseId", r.getCourseId());
            m.put("courseName", c != null ? c.getName() : "未知课程");
            m.put("courseType", c != null ? c.getType() : "");
            m.put("status", r.getStatus());
            m.put("qualificationSource", r.getQualificationSource());
            return m;
        }).toList();
    }

    /** 申请成为某正式课程的授课教练 */
    @Transactional(rollbackFor = Exception.class)
    public Long applyToCourse(Long coachId, Long courseId) {
        CourseEntity course = courseMapper.selectById(courseId);
        if (course == null || !"ON_SHELF".equals(course.getStatus())) {
            throw BusinessException.of(ResultCode.COURSE_NOT_FOUND, "课程不存在或已下架");
        }
        CourseCoachEntity existing = courseCoachMapper.selectOne(
                new LambdaQueryWrapper<CourseCoachEntity>()
                        .eq(CourseCoachEntity::getCourseId, courseId)
                        .eq(CourseCoachEntity::getCoachId, coachId));
        if (existing != null) {
            throw BusinessException.of(ResultCode.CONFLICT, "您已申请或已拥有该课程授课资格");
        }
        CourseCoachEntity relation = new CourseCoachEntity();
        relation.setCourseId(courseId);
        relation.setCoachId(coachId);
        relation.setStatus("PENDING");
        relation.setQualificationSource("APPLY");
        try {
            courseCoachMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of(ResultCode.CONFLICT, "您已申请过该课程");
        }
        return relation.getId();
    }

    /** 管理员审核授课资格 */
    @Transactional(rollbackFor = Exception.class)
    public void approveApplication(Long courseId, Long coachId, Long approverId, boolean approve) {
        CourseCoachEntity relation = courseCoachMapper.selectOne(
                new LambdaQueryWrapper<CourseCoachEntity>()
                        .eq(CourseCoachEntity::getCourseId, courseId)
                        .eq(CourseCoachEntity::getCoachId, coachId));
        if (relation == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "授课资格记录不存在");
        }
        if (!"PENDING".equals(relation.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "该申请已处理");
        }
        relation.setStatus(approve ? "APPROVED" : "REJECTED");
        relation.setApprovedBy(approverId);
        relation.setApprovedAt(LocalDateTime.now());
        courseCoachMapper.updateById(relation);
    }

    /** 停用授课资格 */
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long courseId, Long coachId) {
        CourseCoachEntity relation = courseCoachMapper.selectOne(
                new LambdaQueryWrapper<CourseCoachEntity>()
                        .eq(CourseCoachEntity::getCourseId, courseId)
                        .eq(CourseCoachEntity::getCoachId, coachId));
        if (relation == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "授课资格记录不存在");
        }
        relation.setStatus("DISABLED");
        courseCoachMapper.updateById(relation);
    }

    /** 待审核授课资格列表(管理员) */
    public List<CourseCoachEntity> pendingApplications() {
        return courseCoachMapper.selectList(new LambdaQueryWrapper<CourseCoachEntity>()
                .eq(CourseCoachEntity::getStatus, "PENDING")
                .orderByAsc(CourseCoachEntity::getId));
    }

    /** 某课程的所有授课教练(管理员) */
    public List<CourseCoachEntity> coachesOfCourse(Long courseId) {
        return courseCoachMapper.selectList(new LambdaQueryWrapper<CourseCoachEntity>()
                .eq(CourseCoachEntity::getCourseId, courseId)
                .orderByAsc(CourseCoachEntity::getId));
    }

    /** 校验教练是否具备某课程授课资格(排课校验用) */
    public boolean hasQualification(Long courseId, Long coachId) {
        return courseCoachMapper.selectCount(new LambdaQueryWrapper<CourseCoachEntity>()
                .eq(CourseCoachEntity::getCourseId, courseId)
                .eq(CourseCoachEntity::getCoachId, coachId)
                .eq(CourseCoachEntity::getStatus, "APPROVED")) > 0;
    }
    public void assign(Long courseId, Long coachId, Long operatorId) {
        execution.run(null, null, true, () -> {
            CourseEntity course = courseMapper.selectById(courseId);
            var coach = coachMapper.selectById(coachId);
            if (course == null || !"ON_SHELF".equals(course.getStatus()) || coach == null || !"ACTIVE".equals(coach.getStatus()))
                throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择上架课程与启用教练");
            CourseCoachEntity relation = courseCoachMapper.selectOne(new LambdaQueryWrapper<CourseCoachEntity>()
                    .eq(CourseCoachEntity::getCourseId, courseId).eq(CourseCoachEntity::getCoachId, coachId));
            boolean create = relation == null;
            if (create) { relation = new CourseCoachEntity(); relation.setCourseId(courseId); relation.setCoachId(coachId); relation.setQualificationSource("ADMIN"); }
            relation.setStatus("APPROVED"); relation.setApprovedBy(operatorId); relation.setApprovedAt(com.yoyi.pilates.common.util.StudioTime.now());
            int rows = create ? courseCoachMapper.insert(relation) : courseCoachMapper.updateById(relation);
            if (rows != 1) throw BusinessException.of(ResultCode.CONFLICT, "资格已变化，请刷新");
            return null;
        });
    }
    public List<CourseCoachEntity> approvedQualifications() {
        return courseCoachMapper.selectList(new LambdaQueryWrapper<CourseCoachEntity>().eq(CourseCoachEntity::getStatus, "APPROVED"));
    }

}
