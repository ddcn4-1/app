package org.ddcn41.ticketing_system.dto;

import jakarta.validation.constraints.NotBlank;

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

    // --- 로그인 응답 DTO ---
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
}