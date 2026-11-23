package com.yoyi.pilates.product.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface UserPackageMapper extends BaseMapper<UserPackageEntity> {

    /**
     * 课时扣减(条件更新防超扣): 仅当状态有效且余额充足时扣减.
     *
     * @return 影响行数; 0 = 余额不足或状态异常(调用方必须回滚)
     */
    @Update("UPDATE user_package SET remaining_credits = remaining_credits - #{credits}, "
            + "used_credits = used_credits + #{credits}, version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'ACTIVE' "
            + "AND type <> 'PERIOD' AND remaining_credits >= #{credits}")
    int deductCredits(@Param("id") Long id, @Param("credits") int credits);

    /**
     * 期限卡占用校验(不扣减, 仅确认有效).
     */
    @Update("UPDATE user_package SET version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'ACTIVE' AND type = 'PERIOD'")
    int touchPeriodCard(@Param("id") Long id);

    /**
     * 课时返还(取消预约/退课).
     */
    @Update("UPDATE user_package SET remaining_credits = remaining_credits + #{credits}, "
            + "used_credits = GREATEST(used_credits - #{credits}, 0), version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND type <> 'PERIOD'")
    int refundCredits(@Param("id") Long id, @Param("credits") int credits);

    /** 过期课包批量置为 EXPIRED(仅 ACTIVE 且已到期) */
    @Update("UPDATE user_package SET status = 'EXPIRED' "
            + "WHERE deleted = 0 AND status = 'ACTIVE' AND valid_until < #{today}")
    int expirePackages(@Param("today") LocalDate today);
}
