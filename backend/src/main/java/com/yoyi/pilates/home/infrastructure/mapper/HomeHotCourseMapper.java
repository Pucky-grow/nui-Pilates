package com.yoyi.pilates.home.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.home.infrastructure.entity.HomeHotCourseEntity;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface HomeHotCourseMapper extends BaseMapper<HomeHotCourseEntity> {
 @org.apache.ibatis.annotations.Insert("INSERT INTO home_hot_course (course_id, sort, enabled, deleted) VALUES (#{courseId}, #{sort}, #{enabled}, 0) ON DUPLICATE KEY UPDATE sort=VALUES(sort), enabled=VALUES(enabled), deleted=0, updated_at=CURRENT_TIMESTAMP")
 int savePlacement(HomeHotCourseEntity item);
}
