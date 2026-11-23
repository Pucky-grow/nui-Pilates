package com.yoyi.pilates.content.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.content.application.ContentService;
import com.yoyi.pilates.content.infrastructure.entity.ArticleCategoryEntity;
import com.yoyi.pilates.content.infrastructure.entity.ArticleEntity;
import com.yoyi.pilates.content.infrastructure.entity.BannerEntity;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleCategoryMapper;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleMapper;
import com.yoyi.pilates.content.infrastructure.mapper.BannerMapper;
import com.yoyi.pilates.system.security.RequiresPerm;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容管理接口(文章/Banner).
 */
@RestController
@RequestMapping({"/admin", "/api/admin"})
@RequiredArgsConstructor
public class AdminContentController {

    private final ArticleMapper articleMapper;
    private final ArticleCategoryMapper categoryMapper;
    private final BannerMapper bannerMapper;
    private final ContentService contentService;

    // ---------------- 文章 ----------------

    @GetMapping("/articles")
    @RequiresPerm("content:manage")
    public Result<PageResult<ArticleEntity>> articles(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size) {
        Page<ArticleEntity> p = articleMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<ArticleEntity>()
                        .eq(status != null && !status.isBlank(), ArticleEntity::getStatus, status)
                        .orderByDesc(ArticleEntity::getId));
        return Result.ok(PageResult.of(p, a -> a));
    }

    @PostMapping("/articles")
    @RequiresPerm("content:manage")
    @OpLog(module = "article", action = "create", detail = "#article.title")
    public Result<Long> createArticle(@RequestBody ArticleEntity article) {
        article.setId(null);
        article.setViews(0);
        article.setLikes(0);
        article.setStatus("DRAFT");
        articleMapper.insert(article);
        return Result.ok(article.getId());
    }

    @PutMapping("/articles/{id}")
    @RequiresPerm("content:manage")
    @OpLog(module = "article", action = "update", targetId = "#id")
    public Result<Void> updateArticle(@PathVariable Long id, @RequestBody ArticleEntity article) {
        article.setId(id);
        if ("PUBLISHED".equals(article.getStatus()) && article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }
        articleMapper.updateById(article);
        return Result.ok();
    }

    @DeleteMapping("/articles/{id}")
    @RequiresPerm("content:manage")
    @OpLog(module = "article", action = "delete", targetId = "#id")
    public Result<Void> deleteArticle(@PathVariable Long id) {
        articleMapper.deleteById(id);
        return Result.ok();
    }

    @GetMapping("/article-categories")
    @RequiresPerm("content:manage")
    public Result<List<ArticleCategoryEntity>> categories() {
        return Result.ok(categoryMapper.selectList(null));
    }

    @PostMapping("/article-categories")
    @RequiresPerm("content:manage")
    @OpLog(module = "article", action = "category_create", detail = "#category.name")
    public Result<Long> createCategory(@RequestBody ArticleCategoryEntity category) {
        category.setId(null);
        category.setStatus("ACTIVE");
        categoryMapper.insert(category);
        return Result.ok(category.getId());
    }

    // ---------------- Banner ----------------

    @GetMapping("/banners")
    @RequiresPerm("content:manage")
    public Result<List<BannerEntity>> banners() {
        return Result.ok(bannerMapper.selectList(new LambdaQueryWrapper<BannerEntity>()
                .orderByAsc(BannerEntity::getSort)));
    }

    @PostMapping("/banners")
    @RequiresPerm("content:manage")
    @OpLog(module = "banner", action = "create", detail = "#banner.title")
    public Result<Long> createBanner(@RequestBody BannerEntity banner) {
        banner.setId(null);
        banner.setStatus("ACTIVE");
        bannerMapper.insert(banner);
        contentService.evictBannerCache();
        return Result.ok(banner.getId());
    }

    @PutMapping("/banners/{id}")
    @RequiresPerm("content:manage")
    @OpLog(module = "banner", action = "update", targetId = "#id")
    public Result<Void> updateBanner(@PathVariable Long id, @RequestBody BannerEntity banner) {
        banner.setId(id);
        bannerMapper.updateById(banner);
        contentService.evictBannerCache();
        return Result.ok();
    }

    @DeleteMapping("/banners/{id}")
    @RequiresPerm("content:manage")
    @OpLog(module = "banner", action = "delete", targetId = "#id")
    public Result<Void> deleteBanner(@PathVariable Long id) {
        bannerMapper.deleteById(id);
        contentService.evictBannerCache();
        return Result.ok();
    }
}
