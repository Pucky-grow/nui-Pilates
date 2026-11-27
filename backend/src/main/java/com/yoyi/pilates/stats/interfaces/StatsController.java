package com.yoyi.pilates.stats.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.stats.application.StatsService;
import com.yoyi.pilates.system.security.RequiresPerm;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据看板接口(后台).
 */
@RestController
@RequestMapping({"/admin/statistics", "/api/admin/statistics"})
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/dashboard")
    @RequiresPerm("dashboard:view")
    public Result<Map<String, Object>> dashboard() {
        var user = com.yoyi.pilates.system.security.SecurityUtils.requireUser();
        var view = statsService.dashboard();
        if (!user.isAdminRole() && !user.getRoles().contains("STORE_MANAGER")) {
            view.remove("todayRevenue");
            view.remove("monthRevenue");
        }
        return Result.ok(view);
    }

    @GetMapping("/revenue-trend")
    @RequiresPerm("statistics:view")
    public Result<Map<String, Object>> revenueTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.revenueTrend(days));
    }

    @GetMapping("/top-courses")
    @RequiresPerm("statistics:view")
    public Result<List<Map<String, Object>>> topCourses(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(statsService.topCourses(limit));
    }

    @GetMapping("/coach-stats")
    @RequiresPerm("statistics:view")
    public Result<List<Map<String, Object>>> coachStats() {
        return Result.ok(statsService.coachStats());
    }

    /** 经营账本的课程消费、净收款与运营统计。 */
    @GetMapping("/studio-ledger")
    @RequiresPerm("statistics:view")
    public Result<Map<String, Object>> studioLedger() {
        var user = com.yoyi.pilates.system.security.SecurityUtils.requireUser();
        boolean canViewFinancials = user.isAdminRole() || user.getRoles().contains("STORE_MANAGER");
        return Result.ok(statsService.studioLedgerOverview(canViewFinancials));
    }

    /** 经营分析: 到课率/爽约率/上座率/复购率 */
    @GetMapping("/business-analysis")
    @RequiresPerm("statistics:view")
    public Result<Map<String, Object>> businessAnalysis() {
        return Result.ok(statsService.businessAnalysis());
    }
}
