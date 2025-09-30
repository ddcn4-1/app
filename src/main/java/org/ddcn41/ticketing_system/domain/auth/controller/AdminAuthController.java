package org.ddcn41.ticketing_system.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@RequestMapping("v1/admin/auth")
@Tag(name = "Admin Authentication", description = "APIs for administrator authentication")
public class AdminAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuthAuditService authAuditService;

    public AdminAuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil,
                               UserService userService, AuthAuditService authAuditService) {
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
    @Operation(
            summary = "Admin login",
            description = "Authenticates an administrator. Only users with ADMIN role can login through this endpoint."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Admin login successful",
                    content = @Content(
                            schema = @Schema(implementation = AuthDtos.AuthResponse.class),
                            examples = @ExampleObject(
                                    value = "{\"accessToken\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...\",\"userType\":\"ADMIN\"}"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid credentials",
                    content = @Content(
                            examples = @ExampleObject(
                                    value = "{\"error\":\"Unauthorized\",\"message\":\"관리자 로그인 실패: ...\",\"timestamp\":\"...\"}"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User does not have admin privileges",
                    content = @Content(
                            examples = @ExampleObject(
                                    value = "{\"error\":\"Forbidden\",\"message\":\"관리자 권한이 필요합니다\",\"timestamp\":\"...\"}"
                            )
                    )
            )
    })
    public ResponseEntity<EnhancedAuthResponse> adminLogin(@Valid @RequestBody LoginRequest dto) {
        try {
            String actualUsername = userService.resolveUsernameFromEmailOrUsername(dto.getUsernameOrEmail());
            User user = userService.findByUsername(actualUsername);

            if (!User.Role.ADMIN.equals(user.getRole())) {
                authAuditService.logLoginFailure(actualUsername, "관리자 권한 없음");
                EnhancedAuthResponse errorResponse = EnhancedAuthResponse.failure("관리자 권한이 필요합니다");
                return ResponseEntity.status(403).body(errorResponse);
            }

            // 인증 수행
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(actualUsername, dto.getPassword())
            );

            // JWT 토큰 생성
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

            // 로그인 실패 로그
            authAuditService.logLoginFailure(actualUsername, e.getMessage());

            EnhancedAuthResponse errorResponse = EnhancedAuthResponse.failure("관리자 로그인 실패: " + e.getMessage());
            return ResponseEntity.status(401).body(errorResponse);
        }
    }

    /**
     * 관리자 로그아웃
     * 인증된 관리자만 접근 가능
     */
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> adminLogout(HttpServletRequest request, Authentication authentication) {
        String adminUsername = authentication.getName();

        // Authorization 헤더에서 토큰 추출
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

//    /**
//     * 관리자 상태 확인
//     */
//    @GetMapping("/status")
//    @PreAuthorize("hasRole('ADMIN')")
//    @Operation(
//            summary = "Admin status check",
//            description = "Returns the current authenticated admin user's status and information."
//    )
//    @SecurityRequirement(name = "bearerAuth")
//    @ApiResponses(value = {
//            @ApiResponse(
//                    responseCode = "200",
//                    description = "Admin status retrieved successfully",
//                    content = @Content(
//                            examples = @ExampleObject(
//                                    value = "{\"admin\":\"admin\",\"role\":\"ADMIN\",\"lastLogin\":\"2024-12-01T10:30:00\",\"isActive\":true,\"timestamp\":\"...\"}"
//                            )
//                    )
//            ),
//            @ApiResponse(
//                    responseCode = "401",
//                    description = "Unauthorized - invalid or missing admin token",
//                    content = @Content
//            )
//    })
//    public ResponseEntity<?> adminStatus(Authentication authentication) {
//        String adminUsername = authentication.getName();
//        User admin = userService.findByUsername(adminUsername);
//
//        return ResponseEntity.ok(Map.of(
//                "admin", adminUsername,
//                "role", admin.getRole().name(),
//                "lastLogin", admin.getLastLogin(),
//                "isActive", true,
//                "timestamp", LocalDateTime.now()
//        ));
//    }
}