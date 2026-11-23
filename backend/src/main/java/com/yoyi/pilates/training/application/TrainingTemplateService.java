package com.yoyi.pilates.training.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordTemplateEntity;
import com.yoyi.pilates.training.infrastructure.mapper.TrainingRecordTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 教练训练记录模板服务(常用训练目标/内容模板).
 */
@Service
@RequiredArgsConstructor
public class TrainingTemplateService {

    private final TrainingRecordTemplateMapper templateMapper;

    public List<TrainingRecordTemplateEntity> myTemplates(Long coachId) {
        return templateMapper.selectList(new LambdaQueryWrapper<TrainingRecordTemplateEntity>()
                .eq(TrainingRecordTemplateEntity::getCoachId, coachId)
                .orderByAsc(TrainingRecordTemplateEntity::getSort));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long coachId, TrainingRecordTemplateEntity template) {
        template.setCoachId(coachId);
        templateMapper.insert(template);
        return template.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long coachId, Long templateId, TrainingRecordTemplateEntity template) {
        TrainingRecordTemplateEntity existing = requireTemplate(templateId);
        if (!existing.getCoachId().equals(coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        existing.setName(template.getName());
        existing.setContent(template.getContent());
        existing.setSort(template.getSort());
        templateMapper.updateById(existing);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long coachId, Long templateId) {
        TrainingRecordTemplateEntity existing = requireTemplate(templateId);
        if (!existing.getCoachId().equals(coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        templateMapper.deleteById(templateId);
    }

    private TrainingRecordTemplateEntity requireTemplate(Long templateId) {
        TrainingRecordTemplateEntity template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "模板不存在");
        }
        return template;
    }
}
