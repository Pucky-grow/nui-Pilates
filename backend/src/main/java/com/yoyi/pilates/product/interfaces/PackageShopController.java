package com.yoyi.pilates.product.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.PackageCreditLogEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.product.infrastructure.mapper.PackageCreditLogMapper;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课包商城与我的课包.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PackageShopController {

    private final PackageService packageService;
    private final PackageCreditLogMapper creditLogMapper;

    /** 课包商城列表(公开) */
    @GetMapping("/packages")
    public Result<List<PackageEntity>> packages() {
        return Result.ok(packageService.listOnShelf());
    }

    /** 课包详情(公开) */
    @GetMapping("/packages/{id}")
    public Result<PackageEntity> packageDetail(@PathVariable Long id) {
        return Result.ok(packageService.requirePackage(id));
    }

    /** 我的课包 */
    @GetMapping("/my/packages")
    public Result<List<UserPackageEntity>> myPackages(@RequestParam(required = false) String status) {
        return Result.ok(packageService.myPackages(SecurityUtils.requireMemberId(), status));
    }

    /** 课时流水(我的课包详情页) */
    @GetMapping("/my/packages/{id}/logs")
    public Result<List<PackageCreditLogEntity>> creditLogs(@PathVariable Long id) {
        Long userId = SecurityUtils.requireMemberId();
        UserPackageEntity up = packageService.requireUserPackage(id);
        if (!up.getUserId().equals(userId)) {
            return Result.error(40300, "无权查看");
        }
        return Result.ok(creditLogMapper.selectList(new LambdaQueryWrapper<PackageCreditLogEntity>()
                .eq(PackageCreditLogEntity::getUserPackageId, id)
                .orderByDesc(PackageCreditLogEntity::getId)));
    }
}
