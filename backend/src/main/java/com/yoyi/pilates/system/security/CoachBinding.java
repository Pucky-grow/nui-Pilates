package com.yoyi.pilates.system.security;

/**
 * 教练绑定提供者: 由课程/教练模块(P5)实现, 用于把后台账号映射到 coach.id
 * (教练数据权限: 仅能查看自己授课的课程与学员).
 */
public interface CoachBinding {

    /**
     * @param sysUserId 后台账号ID
     * @return 关联的 coach.id; 非教练账号返回 null
     */
    Long findCoachIdBySysUserId(Long sysUserId);

    /**
     * @param openid 教练绑定的微信 openid
     * @return 关联的 coach.id; 未绑定返回 null
     */
    Long findCoachIdByOpenid(String openid);
    Long findSysUserIdByCoachId(Long coachId);
}
