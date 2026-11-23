package com.yoyi.pilates.feedback.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.feedback.infrastructure.entity.FeedbackEntity;
import com.yoyi.pilates.feedback.infrastructure.mapper.FeedbackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意见反馈服务: 用户提交/查看, 后台处理.
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long userId, FeedbackEntity feedback) {
        feedback.setId(null);
        feedback.setUserId(userId);
        feedback.setStatus("PENDING");
        feedbackMapper.insert(feedback);
        return feedback.getId();
    }

    public List<FeedbackEntity> myFeedbacks(Long userId) {
        return feedbackMapper.selectList(new LambdaQueryWrapper<FeedbackEntity>()
                .eq(FeedbackEntity::getUserId, userId)
                .orderByDesc(FeedbackEntity::getId));
    }

    public List<FeedbackEntity> listByStatus(String status) {
        return feedbackMapper.selectList(new LambdaQueryWrapper<FeedbackEntity>()
                .eq(status != null && !status.isBlank(), FeedbackEntity::getStatus, status)
                .orderByDesc(FeedbackEntity::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void handle(Long feedbackId, String status, String reply, Long handlerId) {
        FeedbackEntity feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "反馈不存在");
        }
        feedback.setStatus(status);
        feedback.setReply(reply);
        feedback.setHandledBy(handlerId);
        feedback.setHandledAt(LocalDateTime.now());
        feedbackMapper.updateById(feedback);
    }
}
