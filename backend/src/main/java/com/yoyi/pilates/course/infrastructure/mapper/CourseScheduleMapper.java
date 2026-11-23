package com.yoyi.pilates.course.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseScheduleMapper extends BaseMapper<CourseScheduleEntity> {
    @Select("SELECT * FROM course_schedule WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    CourseScheduleEntity lockById(@Param("id") Long id);

    /**
     * 占座(数据库层防超卖最后防线): 仅当已约人数 < 上限时 +1.
     *
     * @return 影响行数; 0 = 已满或状态异常
     */
    @Update("UPDATE course_schedule SET booked_count = booked_count + 1, version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'SCHEDULED' AND booked_count < max_capacity")
    int incrBooked(@Param("id") Long id);

    /**
     * 释放座位(取消预约/排课取消退课).
     */
    @Update("UPDATE course_schedule SET booked_count = GREATEST(booked_count - 1, 0), version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND booked_count > 0")
    int decrBooked(@Param("id") Long id);
}
