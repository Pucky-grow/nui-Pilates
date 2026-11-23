package com.yoyi.pilates.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.system.infrastructure.entity.SystemConfigEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfigEntity> {

    /** 物理删除(绕过逻辑删除, 保证唯一索引可复用) */
    @Delete("DELETE FROM system_config WHERE config_key = #{key}")
    int physicalDeleteByKey(@Param("key") String key);
}
