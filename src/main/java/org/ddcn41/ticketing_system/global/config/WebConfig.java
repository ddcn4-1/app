package org.ddcn41.ticketing_system.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 모든 경로에 적용
                .allowedOrigins("http://localhost:3000","https://ddcn41.com", "https://api.ddcn41.com") // React 개발 서버
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600); // 1시간 동안 preflight 캐시

        // 추가로 actuator도 CORS 허용 (테스트용)
        registry.addMapping("/actuator/**")
                .allowedOrigins("*")
                .allowedMethods("GET")
                .allowedHeaders("*");
    }


}
