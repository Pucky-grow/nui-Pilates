package com.yoyi.pilates.content.interfaces;

import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.content.application.ContentService;
import com.yoyi.pilates.content.infrastructure.entity.ArticleCategoryEntity;
import com.yoyi.pilates.content.infrastructure.entity.ArticleEntity;
import com.yoyi.pilates.content.infrastructure.entity.BannerEntity;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容接口: 优仪课堂 / Banner.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @GetMapping("/banners")
    public Result<List<BannerEntity>> banners() {
        return Result.ok(contentService.homeBanners());
    }

    @GetMapping("/article-categories")
    public Result<List<ArticleCategoryEntity>> categories() {
        return Result.ok(contentService.categories());
    }

    @GetMapping("/articles")
    public Result<PageResult<ArticleEntity>> articles(@RequestParam(required = false) Long categoryId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(contentService.pagePublished(categoryId, keyword, page, size), a -> a));
    }

    /** 文章详情(含当前用户点赞/收藏状态; 未登录也可看) */
    @GetMapping("/articles/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        ArticleEntity article = contentService.detail(id);
        Map<String, Object> view = new HashMap<>();
        view.put("article", article);
        SecurityUtils.current().ifPresentOrElse(u -> {
            view.put("liked", contentService.isLiked(u.getId(), id));
            view.put("favorited", contentService.isFavorited(u.getId(), id));
        }, () -> {
            view.put("liked", false);
            view.put("favorited", false);
        });
        return Result.ok(view);
    }

    @PostMapping("/articles/{id}/like")
    public Result<Void> like(@PathVariable Long id) {
        contentService.like(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    @DeleteMapping("/articles/{id}/like")
    public Result<Void> unlike(@PathVariable Long id) {
        contentService.unlike(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    @PostMapping("/articles/{id}/favorite")
    public Result<Void> favorite(@PathVariable Long id) {
        contentService.favorite(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    @DeleteMapping("/articles/{id}/favorite")
    public Result<Void> unfavorite(@PathVariable Long id) {
        contentService.unfavorite(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }
}
