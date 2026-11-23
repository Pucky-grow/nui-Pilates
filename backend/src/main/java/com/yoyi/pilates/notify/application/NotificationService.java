package com.yoyi.pilates.notify.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.notify.infrastructure.entity.NotificationEntity;
import com.yoyi.pilates.notify.infrastructure.entity.NotificationTemplateEntity;
import com.yoyi.pilates.notify.infrastructure.mapper.NotificationMapper;
import com.yoyi.pilates.notify.infrastructure.mapper.NotificationTemplateMapper;
import com.yoyi.pilates.user.application.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息服务: 模板渲染 + 站内信落库 + 订阅消息推送.
 * 幂等: 同一 (userId, bizType, bizId) 只发一次(定时提醒类靠此去重).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationTemplateMapper templateMapper;
    private final WxSubscribeMsgClient wxSubscribeMsgClient;
    private final UserService userService;

    /**
     * 发送站内信(按模板渲染, 业务键幂等).
     *
     * @param templateCode 模板编码(notification_template.code)
     * @param vars         模板变量(替换 {var})
     * @param bizType/bizId 业务键(幂等; 传 null 则不去重)
     */
    @Transactional(rollbackFor = Exception.class)
    public void send(Long userId, String templateCode, Map<String, String> vars,
                     String bizType, String bizId) {
        NotificationTemplateEntity template = templateMapper.selectOne(
                new LambdaQueryWrapper<NotificationTemplateEntity>()
                        .eq(NotificationTemplateEntity::getCode, templateCode));
        String title = render(template != null ? template.getTitleTpl() : "", vars);
        String content = render(template != null ? template.getContentTpl() : "", vars);

        sendText(userId, templateCode, title.isBlank() ? "消息通知" : title, content, bizType, bizId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void sendText(Long userId, String templateCode, String title, String content,
                         String bizType, String bizId) {
        if (bizId != null && alreadySent(userId, bizType, bizId)) return;
        NotificationEntity notification = new NotificationEntity();
        notification.setUserId(userId);
        notification.setTemplateCode(templateCode);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBizType(bizType);
        notification.setBizId(bizId);
        notification.setIsRead(0);
        try { notificationMapper.insert(notification); }
        catch(org.springframework.dao.DuplicateKeyException duplicate) { /* concurrent same business key */ }
    }

    /** The external channel is deliberately disabled pending real WeChat acceptance. */
    public void pushSubscribe(Long userId, String templateCode, Map<String, String> vars, String page) {
        // No subscription grant has been collected/verified. Do not simulate successful delivery.
    }

    public Page<NotificationEntity> pageMy(Long userId, long page, long size) {
        return notificationMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<NotificationEntity>()
                        .eq(NotificationEntity::getUserId, userId)
                        .orderByDesc(NotificationEntity::getId));
    }

    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getUserId, userId)
                .eq(NotificationEntity::getIsRead, 0));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long id) {
        NotificationEntity notification = notificationMapper.selectById(id);
        if (notification == null || !notification.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.NOT_FOUND);
        }
        notification.setIsRead(1);
        notification.setReadAt(LocalDateTime.now());
        notificationMapper.updateById(notification);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(Long userId) {
        List<NotificationEntity> unread = notificationMapper.selectList(
                new LambdaQueryWrapper<NotificationEntity>()
                        .eq(NotificationEntity::getUserId, userId)
                        .eq(NotificationEntity::getIsRead, 0));
        for (NotificationEntity n : unread) {
            n.setIsRead(1);
            n.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(n);
        }
    }

    public boolean alreadySent(Long userId, String bizType, String bizId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getUserId, userId)
                .eq(NotificationEntity::getBizType, bizType)
                .eq(NotificationEntity::getBizId, bizId)) > 0;
    }

    private String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }
}
