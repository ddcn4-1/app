package org.ddcn41.ticketing_system.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.userDetailsService = userDetailsService;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();   // FIXME - The constructor DaoAuthenticationProvider() is deprecated
        authProvider.setUserDetailsService(userDetailsService);                     // FIXME - The method setUserDetailsService(UserDetailsService) from the type DaoAuthenticationProvider is deprecated
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        // 인증 관련 엔드포인트 허용
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/admin/auth/login").permitAll()  // 관리자 로그인만 허용

                        // 헬스체크 허용
                        .requestMatchers("/actuator/**").permitAll()

                        // Swagger / OpenAPI 문서 허용
                        .requestMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // 정적 리소스 및 페이지 라우팅 허용
                        .requestMatchers("/", "/index.html", "/login.html", "/admin-login.html", "/admin.html").permitAll()
                        .requestMatchers("/login", "/admin/login", "/admin/dashboard").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                        // 관리자 전용 API 엔드포인트 (로그인 후 ADMIN 권한 필요)
                        .requestMatchers("/admin/auth/**").hasRole("ADMIN")
                        .requestMatchers("/admin/api/**").hasRole("ADMIN")
                        
                        .requestMatchers("/v1/admin/users/**").hasRole("ADMIN")
                        // .requestMatchers("/v1/admin/schedules/**").hasRole("ADMIN")
                        .requestMatchers("/v1/admin/schedules/**").permitAll()  // 임시로 전체 허용 (개발/테스트용)
                        .requestMatchers("/v1/admin/bookings/**").permitAll()  // 임시로 전체 허용 (개발/테스트용)
                        // .requestMatchers("/v1/admin/bookings/**").hasRole("ADMIN")
                        .requestMatchers("/v1/admin/users/**").hasAnyRole("ADMIN", "DEVOPS")

                        // 공연조회 API 허용
                        .requestMatchers("/v1/performances/**").permitAll()
                        
                        // 예매 관련 API - 인증 필요
                        .requestMatchers("/v1/bookings/**").permitAll()
                        
                        // 좌석 조회 API 허용 (스케줄별 좌석 가용성 조회)
                        .requestMatchers("/api/v1/schedules/**").permitAll()
                        // 공연장 조회/좌석맵 조회 API (GET만 허용)
                        .requestMatchers(HttpMethod.GET, "/api/venues/**").permitAll()

                        // 나머지는 인증 필요
                        .anyRequest().authenticated()

                );

        http.authenticationProvider(authenticationProvider());

        // JWT 필터를 UsernamePasswordAuthenticationFilter 이전에 추가
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
