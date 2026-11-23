package com.yoyi.pilates.home.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.home.infrastructure.entity.HomeHotCourseEntity;
import com.yoyi.pilates.home.infrastructure.entity.HomeTeamCoachEntity;
import com.yoyi.pilates.home.infrastructure.mapper.HomeHotCourseMapper;
import com.yoyi.pilates.home.infrastructure.mapper.HomeTeamCoachMapper;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.system.infrastructure.entity.SystemConfigEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 门店首页运营服务: 热门课程 / 首页教练团队 / 门店信息.
 */
@Service
@RequiredArgsConstructor
public class HomeOperationService {

    private final HomeHotCourseMapper hotCourseMapper;
    private final HomeTeamCoachMapper teamCoachMapper;
    private final CourseMapper courseMapper;
    private final CoachMapper coachMapper;
    private final SystemConfigMapper configMapper;
    private final SystemConfigService configService;

    // ---------------- 热门课程 ----------------

    public List<Map<String, Object>> listHotCourses() {
        List<HomeHotCourseEntity> list = hotCourseMapper.selectList(
                new LambdaQueryWrapper<HomeHotCourseEntity>()
                        .orderByAsc(HomeHotCourseEntity::getSort)
                        .orderByAsc(HomeHotCourseEntity::getId));
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, CourseEntity> courses = courseMapper.selectBatchIds(
                        list.stream().map(HomeHotCourseEntity::getCourseId).toList()).stream()
                .collect(Collectors.toMap(CourseEntity::getId, Function.identity()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (HomeHotCourseEntity h : list) {
            CourseEntity c = courses.get(h.getCourseId());
            Map<String, Object> m = new HashMap<>();
            m.put("id", h.getId());
            m.put("courseId", h.getCourseId());
            m.put("courseName", c != null ? c.getName() : "未知课程");
            m.put("courseType", c != null ? c.getType() : "");
            m.put("sort", h.getSort());
            m.put("enabled", h.getEnabled());
            result.add(m);
        }
        return result;
    }

    /** 全量替换热门课程(校验课程有效性/去重, 事务内删除+插入) */
    @Transactional(rollbackFor = Exception.class)
    public void setHotCourses(List<HomeHotCourseEntity> items) {
        if (items == null) {
            items = List.of();
        }
        int max = configService.getInt("home.hot_course.max", 5);
        if (items.size() > max) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "热门课程最多选择" + max + "门");
        }
        Set<Long> seen = new HashSet<>();
        for (HomeHotCourseEntity item : items) {
            if (item.getCourseId() == null) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "热门课程不能为空");
            }
            if (!seen.add(item.getCourseId())) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "热门课程重复: " + item.getCourseId());
            }
            if (courseMapper.selectById(item.getCourseId()) == null) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "课程不存在: " + item.getCourseId());
            }
        }
        hotCourseMapper.delete(null);
        for (HomeHotCourseEntity item : items) {
            item.setId(null);
            if (item.getSort() == null) item.setSort(0);
            if (item.getEnabled() == null) item.setEnabled(1);
            hotCourseMapper.savePlacement(item);
        }
    }

    // ---------------- 首页教练团队 ----------------

    public List<Map<String, Object>> listTeamCoaches() {
        List<HomeTeamCoachEntity> list = teamCoachMapper.selectList(
                new LambdaQueryWrapper<HomeTeamCoachEntity>()
                        .orderByAsc(HomeTeamCoachEntity::getSort)
                        .orderByAsc(HomeTeamCoachEntity::getId));
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, CoachEntity> coaches = coachMapper.selectBatchIds(
                        list.stream().map(HomeTeamCoachEntity::getCoachId).toList()).stream()
                .collect(Collectors.toMap(CoachEntity::getId, Function.identity()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (HomeTeamCoachEntity h : list) {
            CoachEntity c = coaches.get(h.getCoachId());
            Map<String, Object> m = new HashMap<>();
            m.put("id", h.getId());
            m.put("coachId", h.getCoachId());
            m.put("coachName", c != null ? c.getName() : "未知教练");
            m.put("coachTitle", c != null ? c.getTitle() : "");
            m.put("avatar", c != null ? c.getAvatar() : "");
            m.put("intro", h.getIntro());
            m.put("sort", h.getSort());
            m.put("enabled", h.getEnabled());
            result.add(m);
        }
        return result;
    }

    /** 全量替换首页教练团队(校验教练有效性/去重, 事务内删除+插入) */
    @Transactional(rollbackFor = Exception.class)
    public void setTeamCoaches(List<HomeTeamCoachEntity> items) {
        if (items == null) {
            items = List.of();
        }
        int max = configService.getInt("home.team_coach.max", 5);
        if (items.size() > max) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "教练团队最多选择" + max + "位");
        }
        Set<Long> seen = new HashSet<>();
        for (HomeTeamCoachEntity item : items) {
            if (item.getCoachId() == null) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "教练不能为空");
            }
            if (!seen.add(item.getCoachId())) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "教练团队重复: " + item.getCoachId());
            }
            if (coachMapper.selectById(item.getCoachId()) == null) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "教练不存在: " + item.getCoachId());
            }
        }
        teamCoachMapper.delete(null);
        for (HomeTeamCoachEntity item : items) {
            item.setId(null);
            if (item.getSort() == null) item.setSort(0);
            if (item.getEnabled() == null) item.setEnabled(1);
            teamCoachMapper.savePlacement(item);
        }
    }

    // ---------------- 门店信息 ----------------

    public Map<String, String> getStudioInfo() {
        Map<String, String> view = new HashMap<>();
        for (String key : new String[]{"name", "slogan", "intro", "phone", "address", "hours", "location", "images"}) {
            view.put(key, configService.get("studio." + key, ""));
        }
        return view;
    }

    /** 更新门店信息(写入 system_config, 含入参校验) */
    @Transactional(rollbackFor = Exception.class)
    public void updateStudioInfo(Map<String, String> info) {
        if (info == null) {
            return;
        }
        // 校验: 门店名称/地址/电话非空(有提交时), 电话与营业时间格式, 介绍长度
        if (info.containsKey("name") && StringUtils.isBlank(info.get("name"))) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "门店名称不能为空");
        }
        if (info.containsKey("address") && StringUtils.isBlank(info.get("address"))) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "门店地址不能为空");
        }
        String phone = info.get("phone");
        if (phone != null && !phone.isBlank() && !phone.matches("^(1[3-9]\\d{9}|0\\d{2,3}-?\\d{7,8})$")) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "联系电话格式不正确");
        }
        String hours = info.get("hours");
        if (hours != null && !hours.isBlank()) {
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("^(\\d{2}:\\d{2})-(\\d{2}:\\d{2})$").matcher(hours);
            if (!hm.matches()) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "营业时间格式不正确(应为 HH:mm-HH:mm)");
            }
            if (hm.group(2).compareTo(hm.group(1)) <= 0) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "营业时间结束时间不能早于开始时间");
            }
        }
        String intro = info.get("intro");
        if (intro != null && intro.length() > 500) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "门店介绍不能超过500字");
        }
        for (Map.Entry<String, String> e : info.entrySet()) {
            String key = "studio." + e.getKey();
            SystemConfigEntity entity = configMapper.selectOne(
                    new LambdaQueryWrapper<SystemConfigEntity>().eq(SystemConfigEntity::getConfigKey, key));
            if (entity == null) {
                entity = new SystemConfigEntity();
                entity.setConfigKey(key);
                entity.setConfigValue(e.getValue());
                entity.setDescription("门店信息");
                configMapper.insert(entity);
            } else {
                entity.setConfigValue(e.getValue());
                configMapper.updateById(entity);
            }
            configService.evict(key);
        }
    }
}
