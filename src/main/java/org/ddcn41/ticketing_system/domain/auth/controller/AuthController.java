package org.ddcn41.ticketing_system.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.EnhancedAuthResponse;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.domain.auth.service.AuthAuditService;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.global.config.JwtUtil;
import org.ddcn41.ticketing_system.dto.response.ApiResponse;
import org.ddcn41.ticketing_system.domain.auth.dto.response.LogoutResponse;
import org.ddcn41.ticketing_system.domain.auth.service.AuthService;
import org.ddcn41.ticketing_system.domain.user.service.UserService;
import org.ddcn41.ticketing_system.global.util.TokenExtractor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;



@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuthService authService;
    private final TokenExtractor tokenExtractor;
    private final AuthAuditService authAuditService;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserService userService, AuthService authService, TokenExtractor tokenExtractor, AuthAuditService authAuditService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.authService = authService;
        this.tokenExtractor = tokenExtractor;
        this.authAuditService = authAuditService;
    }

    /**
     * 일반 사용자 로그인 (관리자도 이 엔드포인트 사용 가능하지만 /admin/auth/login 권장)
     */
    @PostMapping("/login")
    public ResponseEntity<EnhancedAuthResponse> login(@Valid @RequestBody LoginRequest dto) {
        try {
            String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());

            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
            );

            String token = jwtUtil.generate(auth.getName());

            // 사용자 정보 조회 및 마지막 로그인 시간 업데이트
            User user = userService.updateUserLoginTime(actualUsername);

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

            AuthDtos.EnhancedAuthResponse errorResponse = EnhancedAuthResponse.failure("로그인 실패: " + e.getMessage());
            return ResponseEntity.status(401).body(errorResponse);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            HttpServletRequest request,
            Authentication authentication) {

        String username = authentication != null ? authentication.getName() : "anonymous";
        String token = tokenExtractor.extractTokenFromRequest(request);

        LogoutResponse logoutData = authService.processLogout(token, username);
        ApiResponse<LogoutResponse> response = ApiResponse.success("로그아웃 완료", logoutData);

        authAuditService.logLogout(username);

        return ResponseEntity.ok(response);
    }
}