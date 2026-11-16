package com.reviewpulse.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.reviewpulse.api.AdminTokenFilter;

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
}
