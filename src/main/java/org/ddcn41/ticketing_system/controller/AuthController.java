package org.ddcn41.ticketing_system.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.dto.AuthDtos.AuthResponse;
import org.ddcn41.ticketing_system.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.config.JwtUtil;
import org.ddcn41.ticketing_system.service.TokenBlacklistService;
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
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil,
                          UserService userService, TokenBlacklistService tokenBlacklistService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    // 로그인 → JWT 발급 (이메일 또는 사용자명 지원)
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest dto) {
        // 이메일인지 사용자명인지 판단해서 실제 사용자명 가져오기
        String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
        );

        String token = jwtUtil.generate(auth.getName());
        // 사용자 역할 정보도 함께 반환 (선택사항)
        String userRole = userService.getUserRole(actualUsername);

        return new AuthResponse(token, userRole);
    }
    // AuthController.java에 추가할 로그아웃 메서드들

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

                // 토큰을 블랙리스트에 추가 (블랙리스트 서비스 사용하는 경우)
                // tokenBlacklistService.blacklistToken(token, expirationTime);

                return ResponseEntity.ok(Map.of(
                        "message", "로그아웃 완료",
                        "username", username,
                        "timestamp", LocalDateTime.now(),
                        "success", true
                ));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "토큰 처리 중 오류 발생",
                        "message", e.getMessage(),
                        "success", false
                ));
            }
        }

        // 토큰이 없는 경우에도 성공 응답 (이미 로그아웃된 상태)
        return ResponseEntity.ok(Map.of(
                "message", "로그아웃 완료",
                "username", username,
                "timestamp", LocalDateTime.now(),
                "success", true
        ));
    }

    @PostMapping("/admin/logout")
    public ResponseEntity<?> adminLogout(HttpServletRequest request, Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "anonymous";

        // 동일한 로그아웃 로직
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                long expirationTime = jwtUtil.extractClaims(token).getExpiration().getTime();
                // tokenBlacklistService.blacklistToken(token, expirationTime);

                return ResponseEntity.ok(Map.of(
                        "message", "관리자 로그아웃 완료",
                        "admin", username,
                        "timestamp", LocalDateTime.now(),
                        "redirectTo", "/admin/login",
                        "success", true
                ));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "관리자 로그아웃 중 오류 발생",
                        "message", e.getMessage(),
                        "success", false
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "관리자 로그아웃 완료",
                "admin", username,
                "timestamp", LocalDateTime.now(),
                "success", true
        ));
    }



}


