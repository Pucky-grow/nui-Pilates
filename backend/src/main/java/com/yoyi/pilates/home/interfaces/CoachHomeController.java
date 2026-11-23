package com.yoyi.pilates.home.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.content.application.ContentService;
import com.yoyi.pilates.content.infrastructure.entity.BannerEntity;
import com.yoyi.pilates.content.infrastructure.mapper.BannerMapper;
import com.yoyi.pilates.home.application.HomeOperationService;
import com.yoyi.pilates.home.infrastructure.entity.HomeHotCourseEntity;
import com.yoyi.pilates.home.infrastructure.entity.HomeTeamCoachEntity;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.RequiresPerm;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 教练门店运营接口: Banner / 热门课程 / 首页教练团队 / 门店信息.
 */
@RestController
@RequestMapping("/api/coach-end/home")
@RequiredArgsConstructor
public class CoachHomeController {

    private final HomeOperationService homeOperationService;
    private final BannerMapper bannerMapper;
    private final ContentService contentService;

    // ---------------- Banner ----------------

    @GetMapping("/banners")
    @RequiresPerm("coach:home:view")
    public Result<List<BannerEntity>> banners() {
        return Result.ok(bannerMapper.selectList(new LambdaQueryWrapper<BannerEntity>()
                .orderByAsc(BannerEntity::getSort)));
    }

    @PostMapping("/banners")
    @RequiresPerm("coach:home:banner")
    @OpLog(module = "home", action = "banner_create", detail = "#banner.title")
    public Result<Long> createBanner(@RequestBody BannerEntity banner) {
        banner.setId(null);
        validateBanner(banner);
        if (banner.getStatus() == null) {
            banner.setStatus("ACTIVE");
        }
        bannerMapper.insert(banner);
        contentService.evictBannerCache();
        return Result.ok(banner.getId());
    }

    @PutMapping("/banners/{id}")
    @RequiresPerm("coach:home:banner")
    @OpLog(module = "home", action = "banner_update", targetId = "#id")
    public Result<Void> updateBanner(@PathVariable Long id, @RequestBody BannerEntity banner) {
        banner.setId(id);
        validateBanner(banner);
        bannerMapper.updateById(banner);
        contentService.evictBannerCache();
        return Result.ok();
    }

    /** 启用/禁用切换(独立接口, 避免与编辑接口的必填校验冲突) */
    @PutMapping("/banners/{id}/status")
    @RequiresPerm("coach:home:banner")
    @OpLog(module = "home", action = "banner_toggle", targetId = "#id")
    public Result<Void> updateBannerStatus(@PathVariable Long id, @RequestBody BannerEntity banner) {
        if (!"ACTIVE".equals(banner.getStatus()) && !"DISABLED".equals(banner.getStatus())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "无效的启用状态");
        }
        BannerEntity exist = bannerMapper.selectById(id);
        if (exist == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "Banner不存在");
        }
        exist.setStatus(banner.getStatus());
        bannerMapper.updateById(exist);
        contentService.evictBannerCache();
        return Result.ok();
    }

    private void validateBanner(BannerEntity banner) {
        if (banner.getImageUrl() == null || banner.getImageUrl().isBlank()) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请上传Banner图片");
        }
        if (banner.getSort() != null && banner.getSort() < 0) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "排序必须为非负整数");
        }
        String linkType = banner.getLinkType();
        if (linkType != null && !"NONE".equals(linkType)) {
            String linkValue = banner.getLinkValue();
            if (linkValue == null || linkValue.isBlank()) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "请填写跳转值");
            }
            if ("URL".equals(linkType)) {
                if (!linkValue.matches("^https?://.+")) {
                    throw BusinessException.of(ResultCode.BAD_REQUEST, "跳转链接需以 http(s):// 开头");
                }
            } else if (!linkValue.matches("^\\d+$")) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "跳转值必须为数字ID");
            }
        }
    }

    @DeleteMapping("/banners/{id}")
    @RequiresPerm("coach:home:banner")
    @OpLog(module = "home", action = "banner_delete", targetId = "#id")
    public Result<Void> deleteBanner(@PathVariable Long id) {
        bannerMapper.deleteById(id);
        contentService.evictBannerCache();
        return Result.ok();
    }

    // ---------------- 热门课程 ----------------

    @GetMapping("/hot-courses")
    @RequiresPerm("coach:home:view")
    public Result<List<Map<String, Object>>> hotCourses() {
        return Result.ok(homeOperationService.listHotCourses());
    }

    @PutMapping("/hot-courses")
    @RequiresPerm("coach:home:hot-course")
    @OpLog(module = "home", action = "hot_course_set")
    public Result<Void> setHotCourses(@RequestBody List<HomeHotCourseEntity> items) {
        homeOperationService.setHotCourses(items);
        return Result.ok();
    }

    // ---------------- 首页教练团队 ----------------

    @GetMapping("/team-coaches")
    @RequiresPerm("coach:home:view")
    public Result<List<Map<String, Object>>> teamCoaches() {
        return Result.ok(homeOperationService.listTeamCoaches());
    }

    @PutMapping("/team-coaches")
    @RequiresPerm("coach:home:coach-team")
    @OpLog(module = "home", action = "team_coach_set")
    public Result<Void> setTeamCoaches(@RequestBody List<HomeTeamCoachEntity> items) {
        homeOperationService.setTeamCoaches(items);
        return Result.ok();
    }

    // ---------------- 门店信息 ----------------

    @GetMapping("/studio")
    @RequiresPerm("coach:home:view")
    public Result<Map<String, String>> studio() {
        return Result.ok(homeOperationService.getStudioInfo());
    }

    @PutMapping("/studio")
    @RequiresPerm("coach:home:studio")
    @OpLog(module = "home", action = "studio_update")
    public Result<Void> updateStudio(@RequestBody Map<String, String> info) {
        homeOperationService.updateStudioInfo(info);
        return Result.ok();
    }

    private LoginUser requireCoach() {
        LoginUser user = SecurityUtils.requireUser();
        if (!user.isCoach() && !user.isAdminRole()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "仅教练账号可访问");
        }
        return user;
    }
}
