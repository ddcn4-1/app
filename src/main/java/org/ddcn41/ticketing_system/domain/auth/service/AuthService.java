package org.ddcn41.ticketing_system.domain.auth.service;

import org.ddcn41.ticketing_system.global.config.JwtUtil;
import org.ddcn41.ticketing_system.domain.auth.dto.response.LogoutResponse;
import org.ddcn41.ticketing_system.global.exception.TokenProcessingException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final JwtUtil jwtUtil;

    public AuthService(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public LogoutResponse processLogout(String token, String username) {
        if (token != null) {
            try {
                // 토큰 만료 시간 계산
                long expirationTime = jwtUtil.extractClaims(token).getExpiration().getTime();
                long timeLeft = (expirationTime - System.currentTimeMillis()) / 1000 / 60;

                return new LogoutResponse(username, timeLeft + "분");
            } catch (Exception e) {
                // 서비스에서는 예외를 그대로 던져서 컨트롤러에서 처리하도록 함
                throw new TokenProcessingException("토큰 처리 중 오류 발생: " + e.getMessage(), e);
            }
        }

        return new LogoutResponse(username);
    }
}
