package com.hmdp.config;

import com.hmdp.utils.LoginIntercertor;
import com.hmdp.utils.RefreshIntercertor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginIntercertor()).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/blog/hot",
                "/shop/**",
                "shop-type/**",
                "/voucher/**",
                "/voucher-order/**"
        ).order(1);
        registry.addInterceptor(new RefreshIntercertor(stringRedisTemplate)).addPathPatterns("/**")
                .order(0);
    }
}
