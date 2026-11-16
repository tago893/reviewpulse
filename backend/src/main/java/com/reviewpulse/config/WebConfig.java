package com.reviewpulse.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.reviewpulse.api.AdminTokenFilter;
import com.reviewpulse.api.RateLimitFilter;

@Configuration(proxyBeanMethods = false)
public class WebConfig {

    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(ReviewPulseProperties properties) {
        FilterRegistrationBean<AdminTokenFilter> registration =
                new FilterRegistrationBean<>(new AdminTokenFilter(properties.admin()));
        registration.addUrlPatterns("/api/admin/*");
        registration.setOrder(10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(ReviewPulseProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties.rateLimit()));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(5);
        return registration;
    }
}
