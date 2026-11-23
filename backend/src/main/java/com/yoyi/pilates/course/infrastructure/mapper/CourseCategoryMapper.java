package com.yoyi.pilates.course.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseCategoryMapper extends BaseMapper<CourseCategoryEntity> {
    @Insert("""
            INSERT INTO course_category (code, name, sort, status, deleted)
            VALUES ('SYSTEM_GENERAL', '通用', 0, 'ACTIVE', 0)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', deleted = 0
            """)
    void ensureDefaultCategory();

    @Select("SELECT id FROM course_category WHERE code = 'SYSTEM_GENERAL' FOR UPDATE")
    Long lockDefaultCategoryId();
}
