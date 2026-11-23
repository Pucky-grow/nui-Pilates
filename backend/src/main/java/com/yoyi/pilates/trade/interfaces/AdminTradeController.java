package com.yoyi.pilates.trade.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.marketing.infrastructure.entity.CouponEntity;
import com.yoyi.pilates.marketing.infrastructure.mapper.CouponMapper;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageItemEntity;
import com.yoyi.pilates.product.infrastructure.mapper.PackageItemMapper;
import com.yoyi.pilates.product.infrastructure.mapper.PackageMapper;
import com.yoyi.pilates.system.security.RequiresPerm;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.trade.application.OrderService;
import com.yoyi.pilates.trade.application.RefundService;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.entity.RefundEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易后台接口: 订单查询/退款 / 课包管理 / 优惠券管理.
 */
@RestController
@RequestMapping({"/admin", "/api/admin"})
@RequiredArgsConstructor
public class AdminTradeController {

    private final OrderService orderService;
    private final RefundService refundService;
    private final PackageMapper packageMapper;
    private final PackageItemMapper packageItemMapper;
    private final CouponMapper couponMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    private boolean canReadAmounts() {
        var user=SecurityUtils.requireUser();
        return user.isAdminRole() || user.getRoles().contains("STORE_MANAGER");
    }
    private Map<String,Object> scopedView(Object row, String... operationalFields) {
        Map<String,Object> view=json.convertValue(row,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        if (!canReadAmounts()) view.keySet().retainAll(java.util.Set.of(operationalFields));
        return view;
    }
    private Map<String,Object> orderView(OrderEntity order) {
        return scopedView(order,"id","orderNo","userId","orderType","status","createdAt","paidAt","closedAt");
    }

    // ---------------- 订单 ----------------

    @GetMapping("/orders")
    @RequiresPerm("order:view")
    public Result<PageResult<Map<String,Object>>> orders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(
                orderService.pageAdmin(orderNo, userId, status, from, to, page, size), this::orderView));
    }

    @GetMapping("/orders/{id}")
    @RequiresPerm("order:view")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        Map<String, Object> view = new HashMap<>();
        view.put("order", orderView(orderService.requireOrder(id)));
        view.put("items", orderService.itemsOf(id).stream().map(item -> scopedView(item,"id","itemType","itemId","itemName","quantity")).toList());
        view.put("refunds", refundService.refundsOf(id).stream().map(refund -> scopedView(refund,"id","status","createdAt","updatedAt")).toList());
        return Result.ok(view);
    }

    @PostMapping("/orders/{id}/refund")
    @RequiresPerm("order:refund")
    @OpLog(module = "order", action = "refund", targetId = "#id", detail = "#reason")
    public Result<Map<String, String>> refund(@PathVariable Long id,
                                              @RequestParam @NotBlank String reason) {
        String operator = SecurityUtils.current().map(u -> u.getName()).orElse("admin");
        String refundNo = refundService.refund(id, reason, operator);
        return Result.ok(Map.of("refundNo", refundNo));
    }

    // ---------------- 课包管理 ----------------

    @GetMapping("/packages")
    @RequiresPerm("package:view")
    public Result<PageResult<PackageEntity>> packages(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size) {
        Page<PackageEntity> p = packageMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<PackageEntity>()
                        .eq(status != null && !status.isBlank(), PackageEntity::getStatus, status)
                        .orderByAsc(PackageEntity::getSort));
        return Result.ok(PageResult.of(p, pkg -> pkg));
    }

    @PostMapping("/packages")
    @RequiresPerm("package:manage")
    @OpLog(module = "package", action = "create", detail = "#pkg.name")
    public Result<Long> createPackage(@Valid @RequestBody PackageEntity pkg) {
        pkg.setId(null);
        packageMapper.insert(pkg);
        return Result.ok(pkg.getId());
    }

    @PutMapping("/packages/{id}")
    @RequiresPerm("package:manage")
    @OpLog(module = "package", action = "update", targetId = "#id")
    public Result<Void> updatePackage(@PathVariable Long id, @Valid @RequestBody PackageEntity pkg) {
        if (packageMapper.selectById(id) == null) {
            throw BusinessException.of(ResultCode.PACKAGE_NOT_FOUND);
        }
        pkg.setId(id);
        packageMapper.updateById(pkg);
        return Result.ok();
    }

    /** 设置课包适用范围(全量替换) */
    @PutMapping("/packages/{id}/items")
    @RequiresPerm("package:manage")
    @OpLog(module = "package", action = "set_items", targetId = "#id")
    public Result<Void> setPackageItems(@PathVariable Long id, @RequestBody List<PackageItemEntity> items) {
        packageItemMapper.delete(new LambdaQueryWrapper<PackageItemEntity>()
                .eq(PackageItemEntity::getPackageId, id));
        for (PackageItemEntity item : items) {
            item.setId(null);
            item.setPackageId(id);
            packageItemMapper.insert(item);
        }
        return Result.ok();
    }

    @GetMapping("/packages/{id}/items")
    @RequiresPerm("package:view")
    public Result<List<PackageItemEntity>> packageItems(@PathVariable Long id) {
        return Result.ok(packageItemMapper.selectList(
                new LambdaQueryWrapper<PackageItemEntity>().eq(PackageItemEntity::getPackageId, id)));
    }

    // ---------------- 优惠券管理 ----------------

    @GetMapping("/coupons")
    @RequiresPerm("coupon:manage")
    public Result<PageResult<CouponEntity>> coupons(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size) {
        Page<CouponEntity> p = couponMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<CouponEntity>().orderByDesc(CouponEntity::getId));
        return Result.ok(PageResult.of(p, c -> c));
    }

    @PostMapping("/coupons")
    @RequiresPerm("coupon:manage")
    @OpLog(module = "coupon", action = "create", detail = "#coupon.name")
    public Result<Long> createCoupon(@Valid @RequestBody CouponEntity coupon) {
        coupon.setId(null);
        coupon.setIssuedCount(0);
        couponMapper.insert(coupon);
        return Result.ok(coupon.getId());
    }

    @PutMapping("/coupons/{id}")
    @RequiresPerm("coupon:manage")
    @OpLog(module = "coupon", action = "update", targetId = "#id")
    public Result<Void> updateCoupon(@PathVariable Long id, @Valid @RequestBody CouponEntity coupon) {
        if (couponMapper.selectById(id) == null) {
            throw BusinessException.of(ResultCode.COUPON_NOT_FOUND);
        }
        coupon.setId(id);
        couponMapper.updateById(coupon);
        return Result.ok();
    }
}
