package com.yoyi.pilates.trade.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.trade.application.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付接口: 创建支付参数 / 微信回调(公开) / 模拟支付(开发).
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** 创建支付参数(小程序 wx.requestPayment; mock 模式返回模拟参数) */
    @PostMapping("/wechat/create")
    public Result<Map<String, Object>> create(@RequestParam Long orderId) {
        return Result.ok(paymentService.createPayParams(SecurityUtils.requireMemberId(), orderId));
    }

    /**
     * 微信支付回调(permitAll, 见 SecurityConfig).
     * 应答格式: {"code":"SUCCESS","message":"成功"}
     */
    @PostMapping("/wechat/callback")
    public Map<String, String> callback(@RequestHeader("Wechatpay-Timestamp") String timestamp,
                                        @RequestHeader("Wechatpay-Nonce") String nonce,
                                        @RequestHeader("Wechatpay-Signature") String signature,
                                        @RequestBody String body) {
        return paymentService.handleWechatCallback(timestamp, nonce, signature, body);
    }

    /** 模拟支付(仅 yoyi.pay.mock-enabled=true 的开发环境可用) */
    @PostMapping("/mock-pay")
    public Result<Void> mockPay(@RequestParam Long orderId) {
        paymentService.mockPay(SecurityUtils.requireMemberId(), orderId);
        return Result.ok();
    }
}
