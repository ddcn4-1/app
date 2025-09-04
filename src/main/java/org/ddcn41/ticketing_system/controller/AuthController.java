package org.ddcn41.ticketing_system.controller;

import jakarta.validation.Valid;
import org.ddcn41.ticketing_system.dto.AuthDtos.AuthResponse;
import org.ddcn41.ticketing_system.dto.AuthDtos.LoginRequest;
import org.ddcn41.ticketing_system.config.JwtUtil;
import org.ddcn41.ticketing_system.service.UserService;
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

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
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
}