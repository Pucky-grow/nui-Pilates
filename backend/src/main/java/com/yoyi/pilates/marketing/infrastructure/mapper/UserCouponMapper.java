package com.yoyi.pilates.marketing.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.marketing.infrastructure.entity.UserCouponEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCouponEntity> {

    /** 下单核销(乐观: 仅 UNUSED → USED, 防重复使用) */
    @Update("UPDATE user_coupon SET status = 'USED', used_at = #{usedAt}, order_id = #{orderId} "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'UNUSED'")
    int markUsedIfUnused(@Param("id") Long id, @Param("userId") Long userId,
                         @Param("orderId") Long orderId, @Param("usedAt") LocalDateTime usedAt);

    /** 订单关闭退回(仅本订单使用过的券) */
    @Update("UPDATE user_coupon SET status = 'UNUSED', used_at = NULL, order_id = NULL "
            + "WHERE id = #{id} AND order_id = #{orderId} AND deleted = 0 AND status = 'USED'")
    int restoreIfUsed(@Param("id") Long id, @Param("orderId") Long orderId);
}
