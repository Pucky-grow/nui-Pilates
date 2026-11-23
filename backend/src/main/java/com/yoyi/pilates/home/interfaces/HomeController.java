package com.yoyi.pilates.home.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.content.application.ContentService;
import com.yoyi.pilates.content.infrastructure.entity.ArticleEntity;
import com.yoyi.pilates.content.infrastructure.entity.BannerEntity;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleMapper;
import com.yoyi.pilates.course.application.CourseQueryService;
import com.yoyi.pilates.course.interfaces.dto.CoachView;
import com.yoyi.pilates.course.interfaces.dto.ScheduleView;
import com.yoyi.pilates.home.application.HomeOperationService;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页聚合接口.
 */
@RestController
@RequiredArgsConstructor
public class HomeController {

    private final ContentService contentService;
    private final CourseQueryService courseQueryService;
    private final PackageService packageService;
    private final ArticleMapper articleMapper;
    private final HomeOperationService homeOperationService;
    private final com.yoyi.pilates.system.application.SystemConfigService configService;

    @GetMapping("/api/home")
    public Result<Map<String, Object>> home() {
        Map<String, Object> view = new HashMap<>();
        List<BannerEntity> banners = contentService.homeBanners();
        view.put("banners", banners);
        // 今日课程
        view.put("todayCourses", courseQueryService.listSchedules(
                com.yoyi.pilates.common.util.StudioTime.today(), com.yoyi.pilates.common.util.StudioTime.today(), null, null, null, null, null, false));
        // 热门课程(运营配置, 空则回退为近3天排期前6)
        List<Map<String, Object>> hotCourses = homeOperationService.listHotCourses();
        if (hotCourses.isEmpty()) {
            hotCourses = courseQueryService.listSchedules(
                    com.yoyi.pilates.common.util.StudioTime.today(), com.yoyi.pilates.common.util.StudioTime.today().plusDays(3),
                    null, null, null, null, null, false).stream().limit(6)
                    .map(s -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("courseId", s.getCourseId());
                        m.put("courseName", s.getCourseName());
                        m.put("courseType", s.getCourseType());
                        return m;
                    }).toList();
        }
        view.put("hotCourses", hotCourses);
        // 教练团队(运营配置, 空则回退为全部教练前4)
        List<Map<String, Object>> coaches = homeOperationService.listTeamCoaches();
        if (coaches.isEmpty()) {
            coaches = courseQueryService.listCoaches().stream().limit(4).map(c -> {
                Map<String, Object> m = new HashMap<>();
                m.put("coachId", c.getId());
                m.put("coachName", c.getName());
                m.put("coachTitle", c.getTitle());
                m.put("avatar", c.getAvatar());
                m.put("intro", c.getBio());
                return m;
            }).toList();
        }
        view.put("coaches", coaches);
        // 新人课包(限购一次的体验课) —— 商业化恢复后启用
        List<PackageEntity> newbie = packageService.listOnShelf().stream()
                .filter(p -> Integer.valueOf(1).equals(p.getIsNewUserOnly())).toList();
        view.put("newbiePackage", newbie.isEmpty() ? null : newbie.get(0));
        // 最新文章
        view.put("articles", articleMapper.selectList(new LambdaQueryWrapper<ArticleEntity>()
                .eq(ArticleEntity::getStatus, "PUBLISHED")
                .orderByDesc(ArticleEntity::getPublishedAt).last("LIMIT 3")));
        // 预约模式(供前端判断是否展示课包/支付)
        view.put("bookingMode", configService.get("booking.paymentMode", "FREE"));
        return Result.ok(view);
    }
}
