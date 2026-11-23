package com.yoyi.pilates.member.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<MemberEntity> {
    @org.apache.ibatis.annotations.Select("""
        <script>SELECT m.* FROM member m JOIN `user` u ON u.id=m.user_id AND u.deleted=0
        WHERE m.deleted=0
        <if test="boundOnly">AND u.openid IS NOT NULL</if>
        <if test="levelId != null">AND m.level_id=#{levelId}</if>
        <if test="keyword != null and keyword != ''">AND (u.nickname LIKE CONCAT('%',#{keyword},'%')
        OR u.phone LIKE CONCAT('%',#{keyword},'%') OR m.member_no LIKE CONCAT('%',#{keyword},'%'))</if>
        ORDER BY m.id DESC</script>
        """)
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<MemberEntity> search(
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MemberEntity> page,
        @org.apache.ibatis.annotations.Param("keyword") String keyword,
        @org.apache.ibatis.annotations.Param("levelId") Long levelId,
        @org.apache.ibatis.annotations.Param("boundOnly") boolean boundOnly);
}
