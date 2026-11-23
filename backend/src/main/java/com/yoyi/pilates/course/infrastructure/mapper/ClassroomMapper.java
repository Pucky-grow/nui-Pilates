package com.yoyi.pilates.course.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClassroomMapper extends BaseMapper<ClassroomEntity> {
}
