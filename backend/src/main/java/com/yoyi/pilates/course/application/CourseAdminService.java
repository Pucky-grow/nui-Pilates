package com.yoyi.pilates.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.mapper.ClassroomMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.interfaces.dto.ClassroomSaveRequest;
import com.yoyi.pilates.course.interfaces.dto.CoachSaveRequest;
import com.yoyi.pilates.course.interfaces.dto.CourseSaveRequest;
import com.yoyi.pilates.system.security.CoachBinding;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 课程/分类/教练/教室的后台管理服务.
 * 同时实现 {@link CoachBinding}: 后台账号 → coach.id(教练数据权限).
 */
@Service
@RequiredArgsConstructor
public class CourseAdminService implements CoachBinding {

    private final CourseMapper courseMapper;
    private final CourseCategoryMapper categoryMapper;
    private final CoachMapper coachMapper;
    private final ClassroomMapper classroomMapper;

    private final com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper scheduleMapper;
    private final com.yoyi.pilates.booking.application.ScheduleExecution execution;

    public CourseEntity courseDetail(Long id) {
        CourseEntity course=courseMapper.selectById(id);
        if(course==null) throw BusinessException.of(ResultCode.COURSE_NOT_FOUND);
        return course;
    }
    public CoachEntity coachDetail(Long id) {var coach=coachMapper.selectById(id);if(coach==null)throw BusinessException.of(ResultCode.NOT_FOUND);return coach;}
    public java.util.List<ClassroomEntity> allRooms() {return classroomMapper.selectList(new LambdaQueryWrapper<ClassroomEntity>().orderByAsc(ClassroomEntity::getId));}
    private void noScheduleHistory(String column,Long id) {
        if(scheduleMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity>().eq(column,id)))
            throw BusinessException.of(ResultCode.CONFLICT,"存在关联排课，请使用停用/下架并保留历史记录");
    }
    private void requireNoUpcoming(String column,Long id) {
        if(scheduleMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity>()
            .eq(column,id).eq("status","SCHEDULED").ge("course_date",com.yoyi.pilates.common.util.StudioTime.today())))
            throw BusinessException.of(ResultCode.CONFLICT,"仍有待开课排课，请先处理关联排课");
    }
    // ---------------- 课程 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long createCourse(CourseSaveRequest request) {
        Long categoryId = request.getCategoryId();
        if (categoryId == null) {
            categoryMapper.ensureDefaultCategory();
            categoryId = categoryMapper.lockDefaultCategoryId();
            if (categoryId == null) {
                throw BusinessException.of(ResultCode.CONFLICT, "默认课程分类初始化失败，请重试");
            }
        } else {
            requireCategory(categoryId);
        }
        CourseEntity course = new CourseEntity();
        BeanUtils.copyProperties(request, course);
        course.setCategoryId(categoryId);
        course.setStatus(StringUtils.defaultIfBlank(request.getStatus(), "ON_SHELF"));
        courseMapper.insert(course);
        return course.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateCourse(Long id, CourseSaveRequest request) {
        CourseEntity course = courseMapper.selectById(id);
        if (course == null) {
            throw BusinessException.of(ResultCode.COURSE_NOT_FOUND);
        }
        if (request.getCategoryId() != null) {
            requireCategory(request.getCategoryId());
        }
        BeanUtils.copyProperties(request, course);
        course.setId(id);
        courseMapper.updateById(course);
    }

    @Transactional(rollbackFor = Exception.class)
    public void toggleCourseShelf(Long id, boolean onShelf) {
        CourseEntity course = courseMapper.selectById(id);
        if (course == null) {
            throw BusinessException.of(ResultCode.COURSE_NOT_FOUND);
        }
        course.setStatus(onShelf ? "ON_SHELF" : "OFF_SHELF");
        courseMapper.updateById(course);
    }

    public void deleteCourse(Long id) {
        execution.run(null,null,true,()->{courseDetail(id);noScheduleHistory("course_id",id);
            if(courseMapper.deleteById(id)!=1)throw BusinessException.of(ResultCode.CONFLICT);return null;});
    }

    public Page<CourseEntity> pageCourses(Long categoryId, String type, String keyword, long page, long size) {
        return courseMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<CourseEntity>()
                        .eq(categoryId != null, CourseEntity::getCategoryId, categoryId)
                        .eq(StringUtils.isNotBlank(type), CourseEntity::getType, type)
                        .like(StringUtils.isNotBlank(keyword), CourseEntity::getName, keyword)
                        .orderByAsc(CourseEntity::getSort));
    }

    // ---------------- 分类 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long createCategory(String code, String name, Integer sort) {
        if(code==null||!code.matches("[A-Za-z0-9_-]{1,32}")||"null".equals(code)||StringUtils.isBlank(name)||name.length()>64||"null".equals(name))
            throw BusinessException.of(ResultCode.BAD_REQUEST,"请填写有效分类编码和名称");
        Long count = categoryMapper.selectCount(
                new LambdaQueryWrapper<CourseCategoryEntity>().eq(CourseCategoryEntity::getCode, code));
        if (count > 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "分类编码已存在");
        }
        CourseCategoryEntity category = new CourseCategoryEntity();
        category.setCode(code);
        category.setName(name);
        category.setSort(sort == null ? 0 : sort);
        category.setStatus("ACTIVE");
        categoryMapper.insert(category);
        return category.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(Long id, String name, Integer sort, String status) {
        if(name!=null&&(name.isBlank()||name.length()>64))throw BusinessException.of(ResultCode.BAD_REQUEST,"分类名称需为1至64字");
        CourseCategoryEntity category = categoryMapper.selectById(id);
        if (category == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "分类不存在");
        }
        if (StringUtils.isNotBlank(name)) {
            category.setName(name);
        }
        if (sort != null) {
            category.setSort(sort);
        }
        if (StringUtils.isNotBlank(status)) {
            if(!java.util.Set.of("ACTIVE","DISABLED").contains(status))throw BusinessException.of(ResultCode.BAD_REQUEST,"分类状态无效");
            if("DISABLED".equals(status)&&courseMapper.exists(new LambdaQueryWrapper<CourseEntity>().eq(CourseEntity::getCategoryId,id).eq(CourseEntity::getStatus,"ON_SHELF")))
                throw BusinessException.of(ResultCode.CONFLICT,"分类下仍有上架课程");
            category.setStatus(status);
        }
        categoryMapper.updateById(category);
    }

    // ---------------- 教练 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long createCoach(CoachSaveRequest request) {
        CoachEntity coach = new CoachEntity();
        BeanUtils.copyProperties(request, coach);
        coach.setRating(BigDecimal.valueOf(5.0));
        coach.setStatus(StringUtils.defaultIfBlank(request.getStatus(), "ACTIVE"));
        coachMapper.insert(coach);
        return coach.getId();
    }

    public void updateCoach(Long id, CoachSaveRequest request) {
        execution.run(null,null,true,()->{
        if("DISABLED".equals(request.getStatus()))requireNoUpcoming("coach_id",id);
        CoachEntity coach = coachMapper.selectById(id);
        if (coach == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教练不存在");
        }
        BeanUtils.copyProperties(request, coach);
        coach.setId(id);
        if(coachMapper.updateById(coach)!=1)throw BusinessException.of(ResultCode.CONFLICT);
        return null;});
    }

    /** 删除教练(逻辑删除, 保留历史数据) */
    public void deleteCoach(Long id) {
        execution.run(null,null,true,()->{
        noScheduleHistory("coach_id",id);
        if (coachMapper.selectById(id) == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教练不存在");
        }
        if(coachMapper.deleteById(id)!=1)throw BusinessException.of(ResultCode.CONFLICT);
        return null;});
    }

    public Page<CoachEntity> pageCoaches(String keyword, long page, long size) {
        return coachMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<CoachEntity>()
                        .like(StringUtils.isNotBlank(keyword), CoachEntity::getName, keyword)
                        .orderByAsc(CoachEntity::getSort));
    }

    // ---------------- 教室 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long createClassroom(ClassroomSaveRequest request) {
        ClassroomEntity classroom = new ClassroomEntity();
        BeanUtils.copyProperties(request, classroom);
        classroom.setStatus(StringUtils.defaultIfBlank(request.getStatus(), "ACTIVE"));
        classroomMapper.insert(classroom);
        return classroom.getId();
    }

    public void updateClassroom(Long id, ClassroomSaveRequest request) {
        execution.run(null,null,true,()->{
        if("DISABLED".equals(request.getStatus()))requireNoUpcoming("classroom_id",id);
        if(scheduleMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity>()
            .eq("classroom_id",id).eq("status","SCHEDULED").ge("course_date",com.yoyi.pilates.common.util.StudioTime.today()).gt("max_capacity",request.getCapacity())))
            throw BusinessException.of(ResultCode.CONFLICT,"教室容量不能低于关联待开课排课容量");
        ClassroomEntity classroom = classroomMapper.selectById(id);
        if (classroom == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教室不存在");
        }
        BeanUtils.copyProperties(request, classroom);
        classroom.setId(id);
        if(classroomMapper.updateById(classroom)!=1)throw BusinessException.of(ResultCode.CONFLICT);
        return null;});
    }

    // ---------------- CoachBinding ----------------

    @Override
    public Long findCoachIdBySysUserId(Long sysUserId) {
        CoachEntity coach = coachMapper.selectOne(
                new LambdaQueryWrapper<CoachEntity>()
                        .eq(CoachEntity::getSysUserId, sysUserId)
                        .eq(CoachEntity::getStatus, "ACTIVE"));
        return coach != null ? coach.getId() : null;
    }

    @Override
    public Long findCoachIdByOpenid(String openid) {
        if (org.apache.commons.lang3.StringUtils.isBlank(openid)) {
            return null;
        }
        CoachEntity coach = coachMapper.selectOne(
                new LambdaQueryWrapper<CoachEntity>()
                        .eq(CoachEntity::getOpenid, openid)
                        .eq(CoachEntity::getStatus, "ACTIVE"));
        return coach != null ? coach.getId() : null;
    }

    /** 教练绑定微信(把 openid 写入 coach 记录) */
    @Transactional(rollbackFor = Exception.class)
    public void bindWechat(Long coachId, String openid) {
        CoachEntity coach = coachMapper.selectById(coachId);
        if (coach == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教练不存在");
        }
        coach.setOpenid(openid);
        coachMapper.updateById(coach);
    }

    /** 解绑微信 */
    @Transactional(rollbackFor = Exception.class)
    public void unbindWechat(Long coachId) {
        CoachEntity coach = coachMapper.selectById(coachId);
        if (coach == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教练不存在");
        }
        coach.setOpenid(null);
        coachMapper.updateById(coach);
    }

    private void requireCategory(Long categoryId) {
        if (categoryMapper.selectById(categoryId) == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "课程分类不存在");
        }
    }
    @Override
    public Long findSysUserIdByCoachId(Long coachId) {
        CoachEntity coach = coachMapper.selectById(coachId);
        return coach != null && "ACTIVE".equals(coach.getStatus()) ? coach.getSysUserId() : null;
    }

}
