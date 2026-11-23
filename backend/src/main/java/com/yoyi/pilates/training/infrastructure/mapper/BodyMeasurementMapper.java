package com.yoyi.pilates.training.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.training.infrastructure.entity.BodyMeasurementEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BodyMeasurementMapper extends BaseMapper<BodyMeasurementEntity> {
}
