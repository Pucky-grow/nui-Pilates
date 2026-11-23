package com.yoyi.pilates.course.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CoachMapper extends BaseMapper<CoachEntity> {
}
