package com.yoyi.pilates.booking.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;

@Mapper
public interface BookingMapper extends BaseMapper<BookingEntity> {
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM booking WHERE schedule_id = #{id}")
    long countHistory(@org.apache.ibatis.annotations.Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM booking b
            JOIN course_schedule s ON s.id = b.schedule_id AND s.deleted = 0
            WHERE b.user_id = #{userId} AND b.deleted = 0
              AND b.status IN ('BOOKED','SIGNED_IN')
              AND s.status = 'SCHEDULED' AND s.id <> #{excludedScheduleId}
              AND s.course_date = #{date} AND s.start_time < #{end} AND s.end_time > #{start}
            """)
    long countOverlaps(@Param("userId") Long userId, @Param("excludedScheduleId") Long excludedScheduleId,
                       @Param("date") LocalDate date, @Param("start") LocalTime start, @Param("end") LocalTime end);
}
