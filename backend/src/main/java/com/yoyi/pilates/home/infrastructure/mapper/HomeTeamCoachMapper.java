package com.yoyi.pilates.home.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.home.infrastructure.entity.HomeTeamCoachEntity;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface HomeTeamCoachMapper extends BaseMapper<HomeTeamCoachEntity> {
 @org.apache.ibatis.annotations.Insert("INSERT INTO home_team_coach (coach_id, sort, enabled, intro, deleted) VALUES (#{coachId}, #{sort}, #{enabled}, #{intro}, 0) ON DUPLICATE KEY UPDATE sort=VALUES(sort), enabled=VALUES(enabled), intro=VALUES(intro), deleted=0, updated_at=CURRENT_TIMESTAMP")
 int savePlacement(HomeTeamCoachEntity item);
}
