package com.yoyi.pilates.trade.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.trade.infrastructure.entity.PaymentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PaymentMapper extends BaseMapper<PaymentEntity> {

    /** 支付单乐观推进(回调幂等防线): 仅 PENDING → SUCCESS */
    @Update("UPDATE payment SET status = 'SUCCESS', transaction_id = #{transactionId}, "
            + "callback_raw = #{callbackRaw}, paid_at = #{paidAt} "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'PENDING'")
    int markSuccessIfPending(@Param("id") Long id,
                             @Param("transactionId") String transactionId,
                             @Param("callbackRaw") String callbackRaw,
                             @Param("paidAt") LocalDateTime paidAt);
}
