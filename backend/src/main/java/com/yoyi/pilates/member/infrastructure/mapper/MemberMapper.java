package com.yoyi.pilates.member.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemberMapper extends BaseMapper<MemberEntity> {
    @Update("""
        UPDATE member SET total_sessions=total_sessions+1,
          last_training_at=CASE WHEN last_training_at IS NULL OR last_training_at < #{finishedAt}
                                THEN #{finishedAt} ELSE last_training_at END,
          updated_at=CURRENT_TIMESTAMP
        WHERE user_id=#{userId} AND deleted=0
        """)
    int recordCompletedSession(@Param("userId") Long userId, @Param("finishedAt") LocalDateTime finishedAt);

    @Select("""
        <script>SELECT m.* FROM member m JOIN `user` u ON u.id=m.user_id
        WHERE m.deleted=0 AND u.deleted=0 AND m.status='ACTIVE' AND u.status='ACTIVE'
        <choose><when test="phone">AND u.phone=#{reference}</when>
        <otherwise>AND m.member_no=#{reference}</otherwise></choose>
        LIMIT 2</script>
        """)
    List<MemberEntity> findForBooking(@Param("reference") String reference, @Param("phone") boolean phone);

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
