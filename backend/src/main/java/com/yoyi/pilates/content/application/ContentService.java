package com.yoyi.pilates.content.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.content.infrastructure.entity.ArticleCategoryEntity;
import com.yoyi.pilates.content.infrastructure.entity.ArticleEntity;
import com.yoyi.pilates.content.infrastructure.entity.ArticleFavoriteEntity;
import com.yoyi.pilates.content.infrastructure.entity.ArticleLikeEntity;
import com.yoyi.pilates.content.infrastructure.entity.BannerEntity;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleCategoryMapper;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleFavoriteMapper;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleLikeMapper;
import com.yoyi.pilates.content.infrastructure.mapper.ArticleMapper;
import com.yoyi.pilates.content.infrastructure.mapper.BannerMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容服务: 文章(优仪课堂)/Banner.
 */
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ArticleMapper articleMapper;
    private final ArticleCategoryMapper categoryMapper;
    private final ArticleLikeMapper likeMapper;
    private final ArticleFavoriteMapper favoriteMapper;
    private final BannerMapper bannerMapper;
    private final StringRedisTemplate redisTemplate;

    // ---------------- Banner ----------------

    /** 首页 Banner(Redis 缓存 5 分钟) */
    public List<BannerEntity> homeBanners() {
        String cached = redisTemplate.opsForValue().get(RedisKeys.HOME_BANNER);
        if (cached != null) {
            return JsonUtils.fromJson(cached, new TypeReference<>() {});
        }
        LocalDateTime now = LocalDateTime.now();
        List<BannerEntity> banners = bannerMapper.selectList(new LambdaQueryWrapper<BannerEntity>()
                .eq(BannerEntity::getStatus, "ACTIVE")
                .and(w -> w.isNull(BannerEntity::getStartAt).or().le(BannerEntity::getStartAt, now))
                .and(w -> w.isNull(BannerEntity::getEndAt).or().ge(BannerEntity::getEndAt, now))
                .orderByAsc(BannerEntity::getSort));
        redisTemplate.opsForValue().set(RedisKeys.HOME_BANNER, JsonUtils.toJson(banners),
                Duration.ofMinutes(5));
        return banners;
    }

    public void evictBannerCache() {
        redisTemplate.delete(RedisKeys.HOME_BANNER);
    }

    // ---------------- 文章 ----------------

    public List<ArticleCategoryEntity> categories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ArticleCategoryEntity>()
                .eq(ArticleCategoryEntity::getStatus, "ACTIVE")
                .orderByAsc(ArticleCategoryEntity::getSort));
    }

    public Page<ArticleEntity> pagePublished(Long categoryId, String keyword, long page, long size) {
        return articleMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<ArticleEntity>()
                        .eq(ArticleEntity::getStatus, "PUBLISHED")
                        .eq(categoryId != null, ArticleEntity::getCategoryId, categoryId)
                        .like(keyword != null && !keyword.isBlank(), ArticleEntity::getTitle, keyword)
                        .orderByDesc(ArticleEntity::getPublishedAt));
    }

    /** 文章详情(浏览量+1) */
    @Transactional(rollbackFor = Exception.class)
    public ArticleEntity detail(Long id) {
        ArticleEntity article = articleMapper.selectById(id);
        if (article == null || !"PUBLISHED".equals(article.getStatus())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "文章不存在");
        }
        articleMapper.incrementViews(id);
        article.setViews(article.getViews() + 1);
        return article;
    }

    /** 点赞(幂等: 唯一索引, 重复点赞直接成功) */
    @Transactional(rollbackFor = Exception.class)
    public void like(Long userId, Long articleId) {
        try {
            ArticleLikeEntity like = new ArticleLikeEntity();
            like.setArticleId(articleId);
            like.setUserId(userId);
            likeMapper.insert(like);
            articleMapper.incrementLikes(articleId);
        } catch (DuplicateKeyException ignored) {
            // 已点过赞, 幂等成功
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlike(Long userId, Long articleId) {
        int rows = likeMapper.delete(new LambdaQueryWrapper<ArticleLikeEntity>()
                .eq(ArticleLikeEntity::getArticleId, articleId)
                .eq(ArticleLikeEntity::getUserId, userId));
        if (rows > 0) {
            articleMapper.decrementLikes(articleId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void favorite(Long userId, Long articleId) {
        try {
            ArticleFavoriteEntity fav = new ArticleFavoriteEntity();
            fav.setArticleId(articleId);
            fav.setUserId(userId);
            favoriteMapper.insert(fav);
        } catch (DuplicateKeyException ignored) {
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void unfavorite(Long userId, Long articleId) {
        favoriteMapper.delete(new LambdaQueryWrapper<ArticleFavoriteEntity>()
                .eq(ArticleFavoriteEntity::getArticleId, articleId)
                .eq(ArticleFavoriteEntity::getUserId, userId));
    }

    public boolean isLiked(Long userId, Long articleId) {
        return likeMapper.selectCount(new LambdaQueryWrapper<ArticleLikeEntity>()
                .eq(ArticleLikeEntity::getArticleId, articleId)
                .eq(ArticleLikeEntity::getUserId, userId)) > 0;
    }

    public boolean isFavorited(Long userId, Long articleId) {
        return favoriteMapper.selectCount(new LambdaQueryWrapper<ArticleFavoriteEntity>()
                .eq(ArticleFavoriteEntity::getArticleId, articleId)
                .eq(ArticleFavoriteEntity::getUserId, userId)) > 0;
    }
}
