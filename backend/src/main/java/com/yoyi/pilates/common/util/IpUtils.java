package com.yoyi.pilates.common.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** Proxy addresses must be explicitly configured; untrusted forwarded headers are ignored. */
public final class IpUtils {
    private static volatile List<IpAddressMatcher> proxies=List.of();
    private IpUtils(){}
    public static void configureTrustedProxies(String value){proxies=Arrays.stream(value.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(IpAddressMatcher::new).toList();}
    public static String getClientIp(HttpServletRequest request){
        String peer=request.getRemoteAddr();
        if(proxies.stream().anyMatch(p->p.matches(peer))){
            String forwarded=request.getHeader("X-Real-IP");
            if(forwarded!=null&&forwarded.matches("[0-9a-fA-F:.]{3,45}")){
                try{return java.net.InetAddress.getByName(forwarded).getHostAddress();}catch(Exception ignored){}
            }
        }
        return peer;
    }
}
