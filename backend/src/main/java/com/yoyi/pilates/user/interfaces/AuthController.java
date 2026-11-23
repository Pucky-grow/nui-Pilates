package com.yoyi.pilates.user.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.JwtAuthenticationFilter;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.user.application.AuthService;
import com.yoyi.pilates.user.application.UserService;
import com.yoyi.pilates.user.application.WxMpClient;
import com.yoyi.pilates.user.interfaces.dto.AdminLoginRequest;
import com.yoyi.pilates.user.interfaces.dto.BindPhoneRequest;
import com.yoyi.pilates.user.interfaces.dto.ProfileUpdateRequest;
import com.yoyi.pilates.user.interfaces.dto.TokenResponse;
import com.yoyi.pilates.user.interfaces.dto.WxLoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证与个人资料接口.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final WxMpClient wxMpClient;

    /** 微信小程序登录 */
    @PostMapping("/wx-login")
    public Result<TokenResponse> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        return Result.ok(authService.wxLogin(request));
    }

    /** 后台/教练端登录 */
    @PostMapping({"/admin-login", "/api/admin-login"})
    public Result<TokenResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        return Result.ok(authService.adminLogin(request.getUsername(), request.getPassword()));
    }

    /** 登出(吊销 token) */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(JwtAuthenticationFilter.HEADER);
        if (StringUtils.isNotBlank(header) && header.startsWith(JwtAuthenticationFilter.PREFIX)) {
            authService.logout(header.substring(JwtAuthenticationFilter.PREFIX.length()));
        }
        return Result.ok();
    }

    /** 当前登录信息: MP_USER 返回个人中心聚合; ADMIN 返回账号与权限 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        LoginUser loginUser = SecurityUtils.requireUser();
        if (loginUser.isAdmin()) {
            return Result.ok(Map.of(
                    "userId", loginUser.getId(),
                    "name", loginUser.getName() == null ? "" : loginUser.getName(),
                    "roles", loginUser.getRoles(),
                    "permissions", loginUser.getPermissions(),
                    "coachId", loginUser.getCoachId() == null ? "" : loginUser.getCoachId()));
        }
        return Result.ok(userService.myProfile(loginUser.getId()));
    }

    /** 更新个人资料(小程序) */
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        userService.updateProfile(SecurityUtils.requireMemberId(), request);
        return Result.ok();
    }

    /** 绑定手机号(小程序 getPhoneNumber, 新版 code 接口) */
    @PostMapping("/bind-phone")
    public Result<Void> bindPhone(@Valid @RequestBody BindPhoneRequest request) {
        Long userId = SecurityUtils.requireMemberId();
        // 生产: code → getuserphonenumber 换取手机号; mock: 取明文 phone
        String phone = wxMpClient.getPhoneByCode(request.getCode(), request.getPhone());
        userService.bindPhone(userId, phone);
        return Result.ok();
    }
}
