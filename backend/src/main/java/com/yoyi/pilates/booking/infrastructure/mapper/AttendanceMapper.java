package com.yoyi.pilates.booking.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.booking.infrastructure.entity.AttendanceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AttendanceMapper extends BaseMapper<AttendanceEntity> {
}
