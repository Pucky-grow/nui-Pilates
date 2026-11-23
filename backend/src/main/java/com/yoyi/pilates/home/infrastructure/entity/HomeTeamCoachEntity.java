package com.yoyi.pilates.home.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("home_team_coach")
public class HomeTeamCoachEntity extends BaseEntity {
    private Long coachId;
    private Integer sort;
    private String intro;
    private Integer enabled;
}
