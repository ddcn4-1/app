package org.ddcn41.ticketing_system.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.dto.AuthDtos.AuthResponse;
import org.ddcn41.ticketing_system.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.config.JwtUtil;
import org.ddcn41.ticketing_system.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    /**
     * 일반 사용자 로그인 (관리자도 이 엔드포인트 사용 가능하지만 /admin/auth/login 권장)
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest dto) {
        // 이메일인지 사용자명인지 판단해서 실제 사용자명 가져오기
        String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
        );

        String token = jwtUtil.generate(auth.getName());
        String userRole = userService.getUserRole(actualUsername);

        return new AuthResponse(token, userRole);
    }

    /**
     * 일반 사용자 로그아웃
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "anonymous";

        // Authorization 헤더에서 토큰 추출
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                // 토큰 만료 시간 가져오기
                long expirationTime = jwtUtil.extractClaims(token).getExpiration().getTime();
                long timeLeft = (expirationTime - System.currentTimeMillis()) / 1000 / 60; // 분 단위

                return ResponseEntity.ok(Map.of(
                        "message", "로그아웃 완료",
                        "username", username,
                        "tokenTimeLeft", timeLeft + "분",
                        "timestamp", LocalDateTime.now(),
                        "success", true
                ));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "토큰 처리 중 오류 발생",
                        "message", e.getMessage(),
                        "username", username,
                        "success", false
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "로그아웃 완료",
                "username", username,
                "timestamp", LocalDateTime.now(),
                "success", true
        ));
    }
}