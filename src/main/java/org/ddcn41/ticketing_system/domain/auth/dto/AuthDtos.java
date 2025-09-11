package org.ddcn41.ticketing_system.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import org.ddcn41.ticketing_system.domain.user.entity.User;

import java.time.LocalDateTime;

/**
 * 로그인/응답 DTO들을 모아둔 컨테이너 클래스
 */
public final class AuthDtos {

    private AuthDtos() { /* util class: 인스턴스화 방지 */ }

    // --- 로그인 요청 DTO (이메일/사용자명 둘 다 지원) ---
    public static class LoginRequest {
        @NotBlank(message = "이메일 또는 사용자명을 입력해주세요")
        private String usernameOrEmail;  // 이메일 또는 username 둘 다 받기
        @NotBlank(message = "비밀번호를 입력해주세요")
        private String password;

        public LoginRequest() { }
        public LoginRequest(String usernameOrEmail, String password) {
            this.usernameOrEmail = usernameOrEmail;
            this.password = password;
        }

        public String getUsernameOrEmail() { return usernameOrEmail; }
        public void setUsernameOrEmail(String usernameOrEmail) { this.usernameOrEmail = usernameOrEmail; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    // --- 기존 로그인 응답 DTO ---
    public static class AuthResponse {
        private String accessToken;
        private String userType;  // USER 또는 ADMIN

        public AuthResponse() { }
        public AuthResponse(String accessToken) {
            this.accessToken = accessToken;
        }
        public AuthResponse(String accessToken, String userType) {
            this.accessToken = accessToken;
            this.userType = userType;
        }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getUserType() { return userType; }
        public void setUserType(String userType) { this.userType = userType; }
    }

    public static class EnhancedAuthResponse {
        private String accessToken;
        private String userType;  // USER 또는 ADMIN
        private UserInfo user;
        private String message;
        private long expiresIn; // 토큰 만료시간 (초)

        public EnhancedAuthResponse() {}

        public EnhancedAuthResponse(String accessToken, String userType, UserInfo user, String message, long expiresIn) {
            this.accessToken = accessToken;
            this.userType = userType;
            this.user = user;
            this.message = message;
            this.expiresIn = expiresIn;
        }

        // 성공 응답 생성
        public static EnhancedAuthResponse success(String token, User user) {
            UserInfo userInfo = new UserInfo(
                    user.getUserId(),
                    user.getUsername(),
                    user.getEmail(),
                    user.getName(),
                    user.getRole().name(),
                    user.getLastLogin()
            );

            return new EnhancedAuthResponse(
                    token,
                    user.getRole().name(),
                    userInfo,
                    "로그인 성공",
                    3600L // 1시간
            );
        }

        // 실패 응답 생성
        public static EnhancedAuthResponse failure(String message) {
            return new EnhancedAuthResponse(null, null, null, message, 0L);
        }

        // Getters and Setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public String getUserType() { return userType; }
        public void setUserType(String userType) { this.userType = userType; }

        public UserInfo getUser() { return user; }
        public void setUser(UserInfo user) { this.user = user; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }

        // 중첩 클래스: 사용자 정보
        public static class UserInfo {
            private Long userId;
            private String username;
            private String email;
            private String name;
            private String role;
            private LocalDateTime lastLogin;

            public UserInfo() {}

            public UserInfo(Long userId, String username, String email, String name, String role, LocalDateTime lastLogin) {
                this.userId = userId;
                this.username = username;
                this.email = email;
                this.name = name;
                this.role = role;
                this.lastLogin = lastLogin;
            }

            // Getters and Setters
            public Long getUserId() { return userId; }
            public void setUserId(Long userId) { this.userId = userId; }

            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }

            public String getEmail() { return email; }
            public void setEmail(String email) { this.email = email; }

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }

            public String getRole() { return role; }
            public void setRole(String role) { this.role = role; }

            public LocalDateTime getLastLogin() { return lastLogin; }
            public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
        }
    }
}