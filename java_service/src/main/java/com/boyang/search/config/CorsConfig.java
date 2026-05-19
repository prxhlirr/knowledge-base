package com.boyang.search.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置（A-1 修复：将 allowedOrigins("*") 改为白名单模式）。
 * 业务功能：控制哪些前端域名可跨域访问后端 API。
 * 安全原因：allowedOrigins("*") 允许任意站点发起跨域请求，攻击者可借 Cookie/Session 发起
 *           CSRF 攻击（删除文档、篡改权限等）。白名单模式将攻击面限制在已知前端域名。
 * 配置：通过 cors.allowed-origins 属性配置，多个来源用逗号分隔。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** CORS 允许的来源白名单（逗号分隔） */
    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://localhost:3000,http://192.168.74.1:5173}")
    private String allowedOrigins;

    @Autowired
    private com.boyang.search.security.JwtAuthInterceptor jwtAuthInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // [A-1 修复] 用配置白名单替代 "*"，消除 CSRF 攻击面
        // 拆分逗号分隔字符串为数组，传入 allowedOrigins（兼容 Spring 5.2+）
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/v1/**"); // 全局拦截 API
    }
}
