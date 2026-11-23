package com.yoyi.pilates.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseProposalEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseProposalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 教练课程方案服务: 教练创建/修改/提交课程方案, 店长/管理员审核.
 *
 * <p>数据权限: 教练只能操作自己的方案; 审核后生成正式课程 + 授课资格关联.</p>
 */
@Service
@RequiredArgsConstructor
public class CourseProposalService {

    private final CourseProposalMapper proposalMapper;
    private final CourseMapper courseMapper;
    private final CourseCoachMapper courseCoachMapper;
    private final CoachMapper coachMapper;

    // ---------------- 教练侧 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long coachId, CourseProposalEntity proposal) {
        proposal.setId(null);
        proposal.setProposalNo(NoGenerator.next("CP"));
        proposal.setCoachId(coachId);
        proposal.setStatus("DRAFT");
        proposalMapper.insert(proposal);
        return proposal.getId();
    }

    /** 修改草稿/被驳回的方案 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long coachId, Long proposalId, CourseProposalEntity proposal) {
        CourseProposalEntity existing = requireProposal(proposalId);
        checkOwnership(existing, coachId);
        if (!"DRAFT".equals(existing.getStatus()) && !"REJECTED".equals(existing.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "仅草稿或已驳回状态可修改");
        }
        proposal.setId(proposalId);
        proposal.setCoachId(coachId);
        proposal.setProposalNo(existing.getProposalNo());
        proposal.setStatus("DRAFT");
        proposal.setRejectReason(null);
        proposalMapper.updateById(proposal);
    }

    /** 提交审核 */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long coachId, Long proposalId) {
        CourseProposalEntity existing = requireProposal(proposalId);
        checkOwnership(existing, coachId);
        if (!"DRAFT".equals(existing.getStatus()) && !"REJECTED".equals(existing.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "当前状态不可提交");
        }
        existing.setStatus("PENDING_REVIEW");
        existing.setRejectReason(null);
        proposalMapper.updateById(existing);
    }

    public List<CourseProposalEntity> myProposals(Long coachId) {
        return proposalMapper.selectList(new LambdaQueryWrapper<CourseProposalEntity>()
                .eq(CourseProposalEntity::getCoachId, coachId)
                .orderByDesc(CourseProposalEntity::getId));
    }

    public CourseProposalEntity detail(Long coachId, Long proposalId) {
        CourseProposalEntity proposal = requireProposal(proposalId);
        // 教练只能看自己的; 管理员看全部(管理员场景由 admin 接口承载)
        if (coachId != null && !proposal.getCoachId().equals(coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        return proposal;
    }

    // ---------------- 管理侧 ----------------

    public List<CourseProposalEntity> pendingProposals() {
        List<CourseProposalEntity> list = proposalMapper.selectList(new LambdaQueryWrapper<CourseProposalEntity>()
                .eq(CourseProposalEntity::getStatus, "PENDING_REVIEW")
                .orderByAsc(CourseProposalEntity::getId));
        fillCoachNames(list);
        return list;
    }

    public List<CourseProposalEntity> allProposals() {
        List<CourseProposalEntity> list = proposalMapper.selectList(new LambdaQueryWrapper<CourseProposalEntity>()
                .orderByDesc(CourseProposalEntity::getId));
        fillCoachNames(list);
        return list;
    }

    /** 批量回填提交教练姓名(避免前端逐条请求) */
    private void fillCoachNames(List<CourseProposalEntity> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> names = coachMapper.selectBatchIds(
                        list.stream().map(CourseProposalEntity::getCoachId)
                                .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(CoachEntity::getId, CoachEntity::getName));
        list.forEach(p -> p.setCoachName(names.getOrDefault(p.getCoachId(), "未知教练")));
    }

    /**
     * 审核通过: 生成正式课程 + 授课资格关联, 方案置 APPROVED.
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long proposalId, Long reviewerId) {
        CourseProposalEntity proposal = requireProposal(proposalId);
        if (!"PENDING_REVIEW".equals(proposal.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "该申请已处理");
        }
        // 1. 创建正式课程
        CourseEntity course = new CourseEntity();
        course.setCategoryId(proposal.getCategoryId());
        course.setName(proposal.getCourseName());
        course.setDescription(proposal.getDescription());
        course.setDurationMinutes(proposal.getDuration() != null ? proposal.getDuration() : 60);
        course.setType(proposal.getCourseType());
        course.setDifficulty(proposal.getDifficulty());
        course.setCreditsCost(proposal.getCreditCost() != null ? proposal.getCreditCost() : 1);
        course.setTargetUsers(proposal.getTargetAudience());
        course.setTrainingFocus(proposal.getTrainingGoal());
        course.setNotices(proposal.getPrecautions());
        course.setSort(0);
        course.setStatus("ON_SHELF");
        courseMapper.insert(course);
        // 2. 建立课程-教练关联(授课资格)
        CourseCoachEntity coachCourse = new CourseCoachEntity();
        coachCourse.setCourseId(course.getId());
        coachCourse.setCoachId(proposal.getCoachId());
        coachCourse.setStatus("APPROVED");
        coachCourse.setQualificationSource("PROPOSAL");
        coachCourse.setApprovedBy(reviewerId);
        coachCourse.setApprovedAt(LocalDateTime.now());
        courseCoachMapper.insert(coachCourse);
        // 3. 方案置通过
        proposal.setStatus("APPROVED");
        proposal.setReviewerId(reviewerId);
        proposal.setReviewedAt(LocalDateTime.now());
        proposal.setCourseId(course.getId());
        proposalMapper.updateById(proposal);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long proposalId, Long reviewerId, String reason) {
        CourseProposalEntity proposal = requireProposal(proposalId);
        if (!"PENDING_REVIEW".equals(proposal.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "该申请已处理");
        }
        proposal.setStatus("REJECTED");
        proposal.setReviewerId(reviewerId);
        proposal.setReviewedAt(LocalDateTime.now());
        proposal.setRejectReason(reason);
        proposalMapper.updateById(proposal);
    }

    // ---------------- 内部 ----------------

    private CourseProposalEntity requireProposal(Long proposalId) {
        CourseProposalEntity proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "课程方案不存在");
        }
        return proposal;
    }

    private void checkOwnership(CourseProposalEntity proposal, Long coachId) {
        if (!proposal.getCoachId().equals(coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能操作自己的课程方案");
        }
    }
}
