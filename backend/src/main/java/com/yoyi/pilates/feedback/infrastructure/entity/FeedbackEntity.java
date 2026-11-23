package com.yoyi.pilates.feedback.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("feedback")
public class FeedbackEntity extends BaseEntity {
    private Long userId;
    private String type;
    private String content;
    private String images;
    private String contact;
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String reply;
}
