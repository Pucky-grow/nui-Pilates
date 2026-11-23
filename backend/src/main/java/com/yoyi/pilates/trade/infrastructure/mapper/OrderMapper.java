package com.yoyi.pilates.trade.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 乐观状态推进(支付回调幂等核心): 仅 PENDING/PAYING → PAID.
     *
     * @return 0 = 已处理过(重复回调), 调用方按幂等成功返回
     */
    @Update("UPDATE `order` SET status = 'PAID', paid_at = #{paidAt} "
            + "WHERE id = #{id} AND deleted = 0 AND status IN ('PENDING','PAYING')")
    int markPaidIfPending(@Param("id") Long id, @Param("paidAt") LocalDateTime paidAt);

    /**
     * 关闭超时未支付订单: 仅 PENDING → CLOSED.
     */
    @Update("UPDATE `order` SET status = 'CLOSED', closed_at = #{closedAt} "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'PENDING'")
    int closeIfPending(@Param("id") Long id, @Param("closedAt") LocalDateTime closedAt);

    /**
     * 退款状态推进: PAID → REFUNDED.
     */
    @Update("UPDATE `order` SET status = 'REFUNDED' "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'PAID'")
    int markRefundedIfPaid(@Param("id") Long id);
}
