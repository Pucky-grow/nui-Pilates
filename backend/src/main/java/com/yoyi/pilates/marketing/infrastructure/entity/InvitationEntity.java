package com.yoyi.pilates.marketing.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("invitation")
public class InvitationEntity extends BaseEntity {
    private Long inviterId;
    private String inviteCode;
    private Long inviteeId;
    private String status;
    private LocalDateTime rewardedAt;
}
