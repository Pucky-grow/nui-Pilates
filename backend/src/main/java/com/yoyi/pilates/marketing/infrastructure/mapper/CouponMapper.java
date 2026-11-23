package com.yoyi.pilates.marketing.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.marketing.infrastructure.entity.CouponEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponMapper extends BaseMapper<CouponEntity> {

    /** 发放库存乐观扣减(防超发) */
    @Update("UPDATE coupon SET issued_count = issued_count + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'ACTIVE' "
            + "AND (total_count = 0 OR issued_count < total_count)")
    int deductStock(@Param("id") Long id);
}
