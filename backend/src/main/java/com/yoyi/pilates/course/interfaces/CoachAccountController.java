package com.yoyi.pilates.course.interfaces;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.application.CourseAdminService;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.user.application.WxMpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 教练账号接口: 微信绑定/解绑(绑定后可用微信一键登录教练端).
 */
@RestController
@RequestMapping("/api/coach-end")
@RequiredArgsConstructor
public class CoachAccountController {

    private final CourseAdminService courseAdminService;
    private final WxMpClient wxMpClient;

    /**
     * 绑定微信: 账号密码登录的教练, 用 wx.login 的 code 换 openid 写入教练档案.
     */
    @PostMapping("/bind-wechat")
    public Result<Void> bindWechat(@RequestBody Map<String, String> body) {
        LoginUser coach = requireCoach();
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "缺少登录凭证");
        }
        String openid = wxMpClient.code2session(code).getOpenid();
        courseAdminService.bindWechat(coach.getCoachId(), openid);
        return Result.ok();
    }

    /** 解绑微信 */
    @PostMapping("/unbind-wechat")
    public Result<Void> unbindWechat() {
        LoginUser coach = requireCoach();
        courseAdminService.unbindWechat(coach.getCoachId());
        return Result.ok();
    }

    private LoginUser requireCoach() {
        LoginUser user = SecurityUtils.requireUser();
        if (!user.isCoach() && !user.isAdminRole()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "仅教练账号可访问");
        }
        if (user.getCoachId() == null) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "账号未关联教练档案");
        }
        return user;
    }
}
