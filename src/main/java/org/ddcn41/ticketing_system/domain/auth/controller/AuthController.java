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
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.AuthResponse;
import org.ddcn41.ticketing_system.domain.auth.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.domain.auth.service.AuthAuditService;
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
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "사용자 인증 API")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuthService authService;
    private final TokenExtractor tokenExtractor;
    private final AuthAuditService authAuditService;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil,
                          UserService userService, AuthService authService,
                          TokenExtractor tokenExtractor, AuthAuditService authAuditService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.authService = authService;
        this.tokenExtractor = tokenExtractor;
        this.authAuditService = authAuditService;
    }

    @PostMapping("/login")
    @Operation(
            summary = "User login",
            description = "Authenticates a user. Returns JWT token for API access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Login successful",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
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
    public AuthResponse login(@Valid @RequestBody LoginRequest dto) {
        String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
        );

        String token = jwtUtil.generate(auth.getName());
        String userRole = userService.getUserRole(actualUsername);

        authAuditService.logLoginSuccess(actualUsername);

        return new AuthResponse(token, userRole);
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

        // 프로젝트의 ApiResponse 클래스 사용 (풀 패키지명으로 명시)
        org.ddcn41.ticketing_system.dto.response.ApiResponse<LogoutResponse> response =
                org.ddcn41.ticketing_system.dto.response.ApiResponse.success("로그아웃 완료", logoutData);

        authAuditService.logLogout(username);

        return ResponseEntity.ok(response);
    }
}