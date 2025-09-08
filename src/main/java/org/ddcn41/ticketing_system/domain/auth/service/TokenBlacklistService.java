package org.ddcn41.ticketing_system.domain.auth.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class TokenBlacklistService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String BLACKLIST_KEY_PREFIX = "blacklisted_token:";

    /**
     * 토큰을 블랙리스트에 추가
     */
    public void blacklistToken(String token, long expirationTimeMs) {
        String key = BLACKLIST_KEY_PREFIX + token;

        // Redis에 저장 (토큰 만료 시간까지만 저장)
        Duration ttl = Duration.ofMillis(expirationTimeMs - System.currentTimeMillis());

        if (ttl.toSeconds() > 0) {
            redisTemplate.opsForValue().set(key, LocalDateTime.now().toString(), ttl);
        }
    }

    /**
     * 토큰이 블랙리스트에 있는지 확인
     */
    public boolean isTokenBlacklisted(String token) {
        String key = BLACKLIST_KEY_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 사용자의 모든 토큰 무효화 (선택사항)
     */
    public void blacklistAllUserTokens(String username) {
        // username을 키로 하는 블랙리스트 (구현 방법은 다양함)
        String userKey = "blacklisted_user:" + username;
        redisTemplate.opsForValue().set(userKey, LocalDateTime.now().toString(), Duration.ofHours(24));
    }
}