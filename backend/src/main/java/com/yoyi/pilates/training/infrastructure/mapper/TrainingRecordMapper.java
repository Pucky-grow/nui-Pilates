package com.yoyi.pilates.training.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TrainingRecordMapper extends BaseMapper<TrainingRecordEntity> {
}
