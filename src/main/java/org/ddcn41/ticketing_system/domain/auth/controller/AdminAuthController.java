package org.ddcn41.ticketing_system.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.AuthResponse;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.global.config.JwtUtil;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuthAuditService authAuditService;

    public AdminAuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserService userService, AuthAuditService authAuditService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.authAuditService = authAuditService;
    }

    /**
     * 관리자 로그인
     * ADMIN 권한이 있는 사용자만 로그인 허용
     */
    @PostMapping("/login")
    public ResponseEntity<?> adminLogin(@Valid @RequestBody LoginRequest dto) {
        try {
            // 사용자명 또는 이메일로 실제 사용자명 찾기
            String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());

            // 사용자 정보 조회하여 ADMIN 권한 확인
            User user = userService.findByUsername(actualUsername);
            if (!User.Role.ADMIN.equals(user.getRole())) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "Forbidden",
                        "message", "관리자 권한이 필요합니다",
                        "timestamp", LocalDateTime.now()
                ));
            }

            // 인증 수행
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
            );

            // JWT 토큰 생성
            String token = jwtUtil.generate(auth.getName());

            // 로그인 성공 로그
            authAuditService.logLoginSuccess(actualUsername);

            // 관리자 로그인 성공 응답
            return ResponseEntity.ok(new AuthResponse(token, "ADMIN"));

        } catch (Exception e) {
            String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());

            // 로그인 실패 로그
            authAuditService.logLoginFailure(actualUsername, e.getMessage());

            return ResponseEntity.status(401).body(Map.of(
                    "error", "Unauthorized",
                    "message", "관리자 로그인 실패: " + e.getMessage(),
                    "timestamp", LocalDateTime.now()
            ));
        }
    }

    /**
     * 관리자 로그아웃
     * 인증된 관리자만 접근 가능
     */
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> adminLogout(HttpServletRequest request, Authentication authentication) {
        String adminUsername = authentication.getName();

        // Authorization 헤더에서 토큰 추출
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                // 토큰 정보 확인 (선택사항)
                long expirationTime = jwtUtil.extractClaims(token).getExpiration().getTime();
                long timeLeft = (expirationTime - System.currentTimeMillis()) / 1000 / 60; // 분 단위

                // 로그아웃 로그
                authAuditService.logLogout(adminUsername);

                return ResponseEntity.ok(Map.of(
                        "message", "관리자 로그아웃 완료",
                        "admin", adminUsername,
                        "tokenTimeLeft", timeLeft + "분",
                        "timestamp", LocalDateTime.now(),
                        "redirectTo", "/admin/login.html",
                        "success", true
                ));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "토큰 처리 중 오류 발생",
                        "message", e.getMessage(),
                        "admin", adminUsername,
                        "success", false
                ));
            }
        }

        // 로그아웃 로그
        authAuditService.logLogout(adminUsername);

        // 토큰이 없는 경우에도 성공 응답
        return ResponseEntity.ok(Map.of(
                "message", "관리자 로그아웃 완료",
                "admin", adminUsername,
                "timestamp", LocalDateTime.now(),
                "success", true
        ));
    }

    /**
     * 관리자 상태 확인
     */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> adminStatus(Authentication authentication) {
        String adminUsername = authentication.getName();
        User admin = userService.findByUsername(adminUsername);

        return ResponseEntity.ok(Map.of(
                "admin", adminUsername,
                "role", admin.getRole().name(),
                "lastLogin", admin.getLastLogin(),
                "isActive", true,
                "timestamp", LocalDateTime.now()
        ));
    }
}