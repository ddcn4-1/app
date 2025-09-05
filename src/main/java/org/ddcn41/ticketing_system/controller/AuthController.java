package org.ddcn41.ticketing_system.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.dto.AuthDtos.AuthResponse;
import org.ddcn41.ticketing_system.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.global.config.JwtUtil;
import org.ddcn41.ticketing_system.dto.response.ApiResponse;
import org.ddcn41.ticketing_system.dto.response.LogoutResponse;
import org.ddcn41.ticketing_system.service.AuthService;
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

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserService userService, AuthService authService, TokenExtractor tokenExtractor) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.authService = authService;
        this.tokenExtractor = tokenExtractor;
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
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            HttpServletRequest request,
            Authentication authentication) {

        String username = authentication != null ? authentication.getName() : "anonymous";
        String token = tokenExtractor.extractTokenFromRequest(request);

        LogoutResponse logoutData = authService.processLogout(token, username);
        ApiResponse<LogoutResponse> response = ApiResponse.success("로그아웃 완료", logoutData);

        return ResponseEntity.ok(response);
    }
}