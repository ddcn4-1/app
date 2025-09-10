package org.ddcn41.ticketing_system.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.EnhancedAuthResponse;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.domain.auth.service.AuthAuditService;
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
import java.util.HashMap;
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

    @PostMapping("/login")
    public ResponseEntity<EnhancedAuthResponse> adminLogin(@Valid @RequestBody LoginRequest dto) {
        try {
            String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());
            User user = userService.findByUsername(actualUsername);

            if (!User.Role.ADMIN.equals(user.getRole())) {
                authAuditService.logLoginFailure(actualUsername, "관리자 권한 없음");
                EnhancedAuthResponse errorResponse = EnhancedAuthResponse.failure("관리자 권한이 필요합니다");
                return ResponseEntity.status(403).body(errorResponse);
            }

            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
            );

            String token = jwtUtil.generate(auth.getName());

            // 마지막 로그인 시간 업데이트
            user = userService.updateUserLoginTime(actualUsername);

            authAuditService.logLoginSuccess(actualUsername);

            EnhancedAuthResponse response = EnhancedAuthResponse.success(token, user);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            String actualUsername;
            try {
                actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());
            } catch (Exception ex) {
                actualUsername = dto.getUsernameOrEmail();
            }

            authAuditService.logLoginFailure(actualUsername, e.getMessage());

            EnhancedAuthResponse errorResponse = EnhancedAuthResponse.failure("관리자 로그인 실패: " + e.getMessage());
            return ResponseEntity.status(401).body(errorResponse);
        }
    }

    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> adminLogout(HttpServletRequest request, Authentication authentication) {
        String adminUsername = authentication.getName();
        String authHeader = request.getHeader("Authorization");

        Map<String, Object> responseMap = new HashMap<>(); // HashMap 생성자 사용
        responseMap.put("message", "관리자 로그아웃 완료");
        responseMap.put("admin", adminUsername);
        responseMap.put("timestamp", LocalDateTime.now());
        responseMap.put("success", true);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                long expirationTime = jwtUtil.extractClaims(token).getExpiration().getTime();
                long timeLeft = (expirationTime - System.currentTimeMillis()) / 1000 / 60;
                responseMap.put("tokenTimeLeft", timeLeft + "분");
            } catch (Exception e) {
                responseMap.put("tokenError", "토큰 처리 중 오류 발생");
            }
        }

        authAuditService.logLogout(adminUsername);
        return ResponseEntity.ok(responseMap);
    }

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