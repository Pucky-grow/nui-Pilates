package com.yoyi.pilates.content.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.content.infrastructure.entity.ArticleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArticleMapper extends BaseMapper<ArticleEntity> {

    @Update("UPDATE article SET views = views + 1 WHERE id = #{id} AND deleted = 0")
    int incrementViews(@Param("id") Long id);

    @Update("UPDATE article SET likes = likes + 1 WHERE id = #{id} AND deleted = 0")
    int incrementLikes(@Param("id") Long id);

    @Update("UPDATE article SET likes = GREATEST(likes - 1, 0) WHERE id = #{id} AND deleted = 0")
    int decrementLikes(@Param("id") Long id);
}
