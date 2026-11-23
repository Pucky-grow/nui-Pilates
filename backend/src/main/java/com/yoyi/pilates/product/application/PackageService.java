package com.yoyi.pilates.product.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageCreditLogEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageItemEntity;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.product.infrastructure.mapper.PackageCreditLogMapper;
import com.yoyi.pilates.product.infrastructure.mapper.PackageItemMapper;
import com.yoyi.pilates.product.infrastructure.mapper.PackageMapper;
import com.yoyi.pilates.product.infrastructure.mapper.UserPackageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 课包服务: 适用课包选择 / 课时扣减与返还 / 课包发放 / 后台调整.
 *
 * <p>一致性原则: 任何课时变动 = 条件 UPDATE(防超扣) + 流水(可审计), 二者同事务.</p>
 */
@Service
@RequiredArgsConstructor
public class PackageService {

    private final PackageMapper packageMapper;
    private final PackageItemMapper packageItemMapper;
    private final UserPackageMapper userPackageMapper;
    private final PackageCreditLogMapper creditLogMapper;

    // ---------------- 查询 ----------------

    public List<PackageEntity> listOnShelf() {
        return packageMapper.selectList(new LambdaQueryWrapper<PackageEntity>()
                .eq(PackageEntity::getStatus, "ON_SHELF")
                .orderByAsc(PackageEntity::getSort));
    }

    public PackageEntity requirePackage(Long packageId) {
        PackageEntity pkg = packageMapper.selectById(packageId);
        if (pkg == null) {
            throw BusinessException.of(ResultCode.PACKAGE_NOT_FOUND);
        }
        return pkg;
    }

    public List<UserPackageEntity> myPackages(Long userId, String status) {
        return userPackageMapper.selectList(new LambdaQueryWrapper<UserPackageEntity>()
                .eq(UserPackageEntity::getUserId, userId)
                .eq(status != null && !status.isBlank(), UserPackageEntity::getStatus, status)
                .orderByDesc(UserPackageEntity::getId));
    }

    public UserPackageEntity requireUserPackage(Long userPackageId) {
        UserPackageEntity up = userPackageMapper.selectById(userPackageId);
        if (up == null) {
            throw BusinessException.of(ResultCode.USER_PACKAGE_NOT_FOUND);
        }
        return up;
    }

    /** 按订单定位发放的课包(退款回收用) */
    public UserPackageEntity findByOrder(Long userId, Long orderId, Long packageId) {
        return userPackageMapper.selectOne(new LambdaQueryWrapper<UserPackageEntity>()
                .eq(UserPackageEntity::getUserId, userId)
                .eq(UserPackageEntity::getOrderId, orderId)
                .eq(UserPackageEntity::getPackageId, packageId));
    }

    /** 更新课包状态(退款回收等) */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(UserPackageEntity up) {
        userPackageMapper.updateById(up);
    }

    /**
     * 为用户挑选适用于该课程的可用课包(指定 userPackageId 则校验指定课包;
     * 否则自动选"剩余课时足够且最早到期"的课包).
     */
    public UserPackageEntity pickApplicablePackage(Long userId, CourseEntity course, Long userPackageId) {
        List<UserPackageEntity> candidates = userPackageMapper.selectList(
                new LambdaQueryWrapper<UserPackageEntity>()
                        .eq(UserPackageEntity::getUserId, userId)
                        .eq(UserPackageEntity::getStatus, "ACTIVE")
                        .ge(UserPackageEntity::getValidUntil, LocalDate.now())
                        .eq(userPackageId != null, UserPackageEntity::getId, userPackageId)
                        .orderByAsc(UserPackageEntity::getValidUntil)
                        .orderByAsc(UserPackageEntity::getId));
        int cost = course.getCreditsCost() == null ? 1 : course.getCreditsCost();
        for (UserPackageEntity up : candidates) {
            if (!isApplicable(up, course)) {
                continue;
            }
            if ("PERIOD".equals(up.getType()) || up.getRemainingCredits() >= cost) {
                return up;
            }
        }
        if (userPackageId != null) {
            throw BusinessException.of(ResultCode.PACKAGE_NOT_APPLICABLE);
        }
        throw BusinessException.of(ResultCode.USER_PACKAGE_NOT_FOUND);
    }

    /**
     * 课包适用性: 私教课只能用私教课包; 团课不能用私教课包;
     * 再看 package_item 的适用范围(ALL/CATEGORY/COURSE/COACH).
     */
    public boolean isApplicable(UserPackageEntity up, CourseEntity course) {
        boolean privateCourse = "PRIVATE".equals(course.getType());
        boolean privatePackage = "PRIVATE".equals(up.getType());
        if (privateCourse != privatePackage) {
            return false;
        }
        List<PackageItemEntity> items = packageItemMapper.selectList(
                new LambdaQueryWrapper<PackageItemEntity>().eq(PackageItemEntity::getPackageId, up.getPackageId()));
        for (PackageItemEntity item : items) {
            switch (item.getItemType()) {
                case "ALL":
                    return true;
                case "CATEGORY":
                    if (item.getItemId() != null && item.getItemId().equals(course.getCategoryId())) {
                        return true;
                    }
                    break;
                case "COURSE":
                    if (item.getItemId() != null && item.getItemId().equals(course.getId())) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        // 未配置任何适用项 = 仅私教课包适用私教课, 其余视为全部团课适用
        return items.isEmpty() && !privatePackage;
    }

    // ---------------- 课时扣减/返还(必须在调用方事务内) ----------------

    /**
     * 预约扣课时: 条件 UPDATE 防超扣 + 流水.
     * 调用方必须处于事务中, 失败抛异常触发整体回滚.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void deduct(UserPackageEntity up, int credits, String bizType, String bizId) {
        int rows = "PERIOD".equals(up.getType())
                ? userPackageMapper.touchPeriodCard(up.getId())
                : userPackageMapper.deductCredits(up.getId(), credits);
        if (rows == 0) {
            throw BusinessException.of(ResultCode.CREDITS_NOT_ENOUGH);
        }
        // 更新成功后读取的即为变动后余额
        writeLog(up, "DEDUCT", -credits, balanceOf(up), bizType, bizId, "system", "预约扣课时");
    }

    /**
     * 取消返还课时.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void refund(UserPackageEntity up, int credits, String bizType, String bizId, String remark) {
        if ("PERIOD".equals(up.getType())) {
            writeLog(up, "REFUND", 0, balanceOf(up), bizType, bizId, "system", remark + "(期限卡不增减课时)");
            return;
        }
        userPackageMapper.refundCredits(up.getId(), credits);
        writeLog(up, "REFUND", credits, balanceOf(up), bizType, bizId, "system", remark);
    }

    // ---------------- 发放(支付成功, P7 调用) ----------------

    /**
     * 发放课包: 依赖 user_package(order_id, package_id) 唯一索引保证幂等,
     * 重复发放(重复回调)直接命中唯一键冲突, 由调用方按幂等成功处理.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public UserPackageEntity grant(Long userId, Long orderId, PackageEntity pkg) {
        UserPackageEntity up = new UserPackageEntity();
        up.setUserId(userId);
        up.setPackageId(pkg.getId());
        up.setOrderId(orderId);
        up.setName(pkg.getName());
        up.setType(pkg.getType());
        up.setTotalCredits(pkg.getTotalCredits());
        up.setRemainingCredits(pkg.getTotalCredits());
        up.setUsedCredits(0);
        up.setValidFrom(LocalDate.now());
        up.setValidUntil(LocalDate.now().plusDays(pkg.getValidDays()));
        up.setStatus("ACTIVE");
        userPackageMapper.insert(up);
        if (pkg.getTotalCredits() > 0) {
            writeLog(up, "GRANT", pkg.getTotalCredits(), pkg.getTotalCredits(),
                    "ORDER", String.valueOf(orderId), "system", "购买课包发放");
        }
        return up;
    }

    // ---------------- 后台调整(P12 调用) ----------------

    @Transactional(rollbackFor = Exception.class)
    public void adjust(Long userPackageId, int delta, String operator, String remark) {
        UserPackageEntity up = requireUserPackage(userPackageId);
        if (delta < 0) {
            if (userPackageMapper.deductCredits(userPackageId, -delta) == 0) {
                throw BusinessException.of(ResultCode.CREDITS_NOT_ENOUGH, "剩余课时不足, 无法扣减");
            }
        } else {
            userPackageMapper.refundCredits(userPackageId, delta);
        }
        writeLog(up, "ADJUST", delta, balanceOf(up), "ADMIN", String.valueOf(userPackageId),
                operator, remark);
    }

    // ---------------- 内部 ----------------

    private int balanceOf(UserPackageEntity up) {
        UserPackageEntity fresh = userPackageMapper.selectById(up.getId());
        return fresh.getRemainingCredits();
    }

    private void writeLog(UserPackageEntity up, String changeType, int amount, int balanceAfter,
                          String bizType, String bizId, String operator, String remark) {
        PackageCreditLogEntity logEntity = new PackageCreditLogEntity();
        logEntity.setUserPackageId(up.getId());
        logEntity.setUserId(up.getUserId());
        logEntity.setChangeType(changeType);
        logEntity.setChangeAmount(amount);
        logEntity.setBalanceAfter(balanceAfter);
        logEntity.setBizType(bizType);
        logEntity.setBizId(bizId);
        logEntity.setOperator(operator);
        logEntity.setRemark(remark);
        creditLogMapper.insert(logEntity);
    }
}
