package com.yoyi.pilates.common.config;
import com.yoyi.pilates.common.util.IpUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
@Configuration
public class TrustedProxyConfiguration {
    public TrustedProxyConfiguration(@Value("${yoyi.security.trusted-proxies:}") String proxies){IpUtils.configureTrustedProxies(proxies);}
}
