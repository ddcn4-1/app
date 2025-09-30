package org.ddcn41.ticketing_system.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.EnhancedAuthResponse;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.domain.auth.service.AuthAuditService;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.global.config.JwtUtil;
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
@RequestMapping("v1/auth")
@Tag(name = "Authentication", description = "사용자 인증 API")
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
    @Operation(
            summary = "User login",
            description = "Authenticates a user. Returns JWT token for API access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Login successful",
                    content = @Content(schema = @Schema(implementation = AuthDtos.AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid credentials"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request format"
            )
    })
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
    @Operation(
            summary = "User logout",
            description = "Logs out the authenticated user and invalidates the JWT token."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Logout successful"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - invalid or missing token"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Token processing error"
            )
    })
    public ResponseEntity<org.ddcn41.ticketing_system.dto.response.ApiResponse<LogoutResponse>> logout(
            HttpServletRequest request,
            Authentication authentication) {

        String username = authentication != null ? authentication.getName() : "anonymous";
        String token = tokenExtractor.extractTokenFromRequest(request);

        LogoutResponse logoutData = authService.processLogout(token, username);
        org.ddcn41.ticketing_system.dto.response.ApiResponse<LogoutResponse> response = org.ddcn41.ticketing_system.dto.response.ApiResponse.success("로그아웃 완료", logoutData);

        authAuditService.logLogout(username);

        return ResponseEntity.ok(response);
    }
}