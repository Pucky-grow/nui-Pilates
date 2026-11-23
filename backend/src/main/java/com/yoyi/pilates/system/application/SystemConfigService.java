package com.yoyi.pilates.system.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.system.infrastructure.entity.SystemConfigEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 系统配置读取: DB 为准, Redis 60s 短缓存.
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private static final String CACHE = "pilates:config:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final SystemConfigMapper configMapper;
    private final StringRedisTemplate redisTemplate;

    public String get(String key, String defaultValue) {
        String cached = redisTemplate.opsForValue().get(CACHE + key);
        if (cached != null) {
            return cached;
        }
        SystemConfigEntity entity = configMapper.selectOne(
                new LambdaQueryWrapper<SystemConfigEntity>().eq(SystemConfigEntity::getConfigKey, key));
        String value = entity != null ? entity.getConfigValue() : defaultValue;
        redisTemplate.opsForValue().set(CACHE + key, value == null ? "" : value, TTL);
        return value;
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 逗号分隔整数列表, 如 "7,3" → [7,3] */
    public List<Integer> getIntList(String key) {
        String v = get(key, "");
        if (v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Integer::parseInt).toList();
    }

    public void evict(String key) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { redisTemplate.delete(CACHE + key); }
                    });
        } else {
            redisTemplate.delete(CACHE + key);
        }
    }

    /** 设置配置(物理 upsert, 供后台/门店运营修改规则) */
    public void set(String key, String value) {
        SystemConfigEntity existing = configMapper.selectOne(
                new LambdaQueryWrapper<SystemConfigEntity>().eq(SystemConfigEntity::getConfigKey, key));
        if (existing == null) {
            configMapper.physicalDeleteByKey(key);
            SystemConfigEntity entity = new SystemConfigEntity();
            entity.setConfigKey(key);
            entity.setConfigValue(value);
            configMapper.insert(entity);
        } else {
            existing.setConfigValue(value);
            configMapper.updateById(existing);
        }
        evict(key);
    }
}
