package com.yoyi.pilates.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web 层配置: CORS / Jackson(JavaTime) / 本地上传文件静态映射.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${yoyi.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${yoyi.upload.url-prefix:/uploads}")
    private String urlPrefix;

    @Value("${yoyi.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Value("${yoyi.public-base-url:}")
    private String publicBase;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 管理后台(Vite dev: 5173)与生产同源部署都需要跨域支持
        registry.addMapping("/**")
                .allowedOrigins((allowedOrigins.isBlank() ? publicBase : allowedOrigins).isBlank() ? new String[0] : (allowedOrigins.isBlank() ? publicBase : allowedOrigins).split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /** Java 8 时间类型序列化为 "yyyy-MM-dd HH:mm:ss" 而非时间戳. */
    public MappingJackson2HttpMessageConverter jacksonConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new MappingJackson2HttpMessageConverter(mapper);
    }
}
