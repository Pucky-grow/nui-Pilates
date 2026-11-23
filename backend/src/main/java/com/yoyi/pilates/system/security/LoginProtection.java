package com.yoyi.pilates.system.security;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.IpUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LoginProtection {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n",Long.class);
    public String clientIp(){return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a?IpUtils.getClientIp(a.getRequest()):"internal";}
    public static String digest(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String account(String username){return "pilates:login:failure:account:"+digest(username.trim().toLowerCase(Locale.ROOT));}
    private String ip(String value){return "pilates:login:failure:ip:"+digest(value);}
    private long count(String key){String value=redis.opsForValue().get(key);return value==null?0:Long.parseLong(value);}
    public void before(String username,String ip){
        Long requests=redis.execute(INCREMENT,List.of("pilates:login:requests:"+digest(ip)),"60");
        if((requests!=null&&requests>100)||count(account(username))>=10||count(ip(ip))>=30)reject();
    }
    public void failure(String username,String ip){redis.execute(INCREMENT,List.of(account(username)),"300");redis.execute(INCREMENT,List.of(ip(ip)),"300");}
    public void success(String username){redis.delete(account(username));}
    private void reject(){throw BusinessException.of(ResultCode.UNAUTHORIZED,"用户名或密码错误，请稍后重试");}
}
