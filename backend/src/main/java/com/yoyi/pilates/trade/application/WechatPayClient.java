package com.yoyi.pilates.trade.application;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付 V3 客户端:
 * - JSAPI 统一下单(商户私钥签名 Authorization)
 * - 小程序支付参数二次签名
 * - 回调验签(微信平台证书) + resource AES-256-GCM 解密
 *
 * <p>所有密钥经环境变量/文件路径注入, 绝不硬编码. mock 模式下本类不被调用.</p>
 */
@Slf4j
@Component
public class WechatPayClient {

    private static final String JSAPI_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi";
    private static final String REFUND_URL = "https://api.mch.weixin.qq.com/v3/refund/domestic/refunds";

    private final RestTemplate restTemplate;
    private final String appid;
    private final String mchid;
    private final String apiV3Key;
    private final String privateKeyPath;
    private final String certSerialNo;
    private final String platformCertPath;
    private final String notifyUrl;

    public WechatPayClient(RestTemplate restTemplate,
                           @Value("${yoyi.wx.appid:}") String appid,
                           @Value("${yoyi.pay.mchid:}") String mchid,
                           @Value("${yoyi.pay.api-v3-key:}") String apiV3Key,
                           @Value("${yoyi.pay.private-key-path:}") String privateKeyPath,
                           @Value("${yoyi.pay.cert-serial-no:}") String certSerialNo,
                           @Value("${yoyi.pay.platform-cert-path:}") String platformCertPath,
                           @Value("${yoyi.pay.notify-url:}") String notifyUrl) {
        this.restTemplate = restTemplate;
        this.appid = appid;
        this.mchid = mchid;
        this.apiV3Key = apiV3Key;
        this.privateKeyPath = privateKeyPath;
        this.certSerialNo = certSerialNo;
        this.platformCertPath = platformCertPath;
        this.notifyUrl = notifyUrl;
    }

    /**
     * JSAPI 下单并生成小程序调起支付参数.
     *
     * @return wx.requestPayment 所需参数(appId/timeStamp/nonceStr/package/signType/paySign)
     */
    public Map<String, String> createJsapiPayment(String outTradeNo, String description,
                                                  BigDecimal amountYuan, String openid) {
        Map<String, Object> body = new HashMap<>();
        body.put("appid", appid);
        body.put("mchid", mchid);
        body.put("description", description.length() > 127 ? description.substring(0, 127) : description);
        body.put("out_trade_no", outTradeNo);
        body.put("notify_url", notifyUrl);
        body.put("amount", Map.of("total", toFen(amountYuan), "currency", "CNY"));
        body.put("payer", Map.of("openid", openid));

        String bodyJson = JsonUtils.toJson(body);
        String response = postWithSign(JSAPI_URL, bodyJson);
        Map<String, Object> resp = JsonUtils.fromJson(response, Map.class);
        String prepayId = (String) resp.get("prepay_id");
        if (prepayId == null) {
            log.error("微信下单失败: {}", response);
            throw BusinessException.of(ResultCode.PAY_FAILED, "微信支付下单失败");
        }
        return buildPayParams(prepayId);
    }

    /** 申请退款 */
    public String refund(String transactionId, String outTradeNo, String refundNo,
                         BigDecimal refundYuan, BigDecimal totalYuan) {
        Map<String, Object> body = new HashMap<>();
        body.put("transaction_id", transactionId);
        body.put("out_trade_no", outTradeNo);
        body.put("out_refund_no", refundNo);
        body.put("reason", "用户申请退款");
        body.put("notify_url", notifyUrl.replace("callback", "refund-callback"));
        body.put("amount", Map.of(
                "refund", toFen(refundYuan), "total", toFen(totalYuan), "currency", "CNY"));
        return postWithSign(REFUND_URL, JsonUtils.toJson(body));
    }

    /**
     * 回调验签: 使用微信支付平台证书公钥验证 Wechatpay-Signature.
     *
     * @param timestamp Wechatpay-Timestamp
     * @param nonce     Wechatpay-Nonce
     * @param signature Wechatpay-Signature
     * @param body      回调原始报文
     */
    public boolean verifyCallback(String timestamp, String nonce, String signature, String body) {
        try {
            String message = timestamp + "\n" + nonce + "\n" + body + "\n";
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initVerify(loadPlatformCert());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return signer.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            log.error("回调验签异常", e);
            return false;
        }
    }

    /**
     * 解密回调 resource(AES-256-GCM, key=apiV3Key).
     *
     * @return 解密后的支付结果 JSON(含 out_trade_no/transaction_id/trade_state)
     */
    public String decryptResource(String associatedData, String nonce, String ciphertext) {
        try {
            byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("回调报文解密失败", e);
            throw BusinessException.of(ResultCode.PAY_SIGN_INVALID, "回调报文解密失败");
        }
    }

    // ---------------- 内部 ----------------

    private String postWithSign(String url, String bodyJson) {
        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String message = "POST\n" + url.replace("https://api.mch.weixin.qq.com", "") + "\n"
                    + timestamp + "\n" + nonce + "\n" + bodyJson + "\n";
            String sign = sign(message);
            String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchid + "\","
                    + "nonce_str=\"" + nonce + "\",signature=\"" + sign + "\","
                    + "timestamp=\"" + timestamp + "\",serial_no=\"" + certSerialNo + "\"";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", authorization);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(bodyJson, headers), String.class);
            return resp.getBody();
        } catch (Exception e) {
            log.error("微信支付接口调用失败 url={}", url, e);
            throw BusinessException.of(ResultCode.PAY_FAILED, "微信支付接口调用失败");
        }
    }

    /** 小程序支付参数: 用商户私钥对 appId\ntimeStamp\nnonceStr\npackage\n 签名 */
    private Map<String, String> buildPayParams(String prepayId) {
        String timeStamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String packageValue = "prepay_id=" + prepayId;
        String message = appid + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
        Map<String, String> params = new HashMap<>();
        params.put("appId", appid);
        params.put("timeStamp", timeStamp);
        params.put("nonceStr", nonceStr);
        params.put("packageValue", packageValue);
        params.put("signType", "RSA");
        params.put("paySign", sign(message));
        return params;
    }

    private String sign(String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(loadPrivateKey());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.PAY_FAILED, "支付签名失败");
        }
    }

    private PrivateKey loadPrivateKey() {
        try {
            String pem = Files.readString(Path.of(privateKeyPath))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw BusinessException.of(ResultCode.PAY_FAILED, "商户私钥加载失败, 请检查配置");
        }
    }

    private X509Certificate loadPlatformCert() {
        try (var in = Files.newInputStream(Path.of(platformCertPath))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.PAY_SIGN_INVALID, "微信平台证书加载失败, 请检查配置");
        }
    }

    private int toFen(BigDecimal yuan) {
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
