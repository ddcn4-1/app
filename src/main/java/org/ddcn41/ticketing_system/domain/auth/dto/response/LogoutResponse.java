package org.ddcn41.ticketing_system.domain.auth.dto.response;

import lombok.Getter;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogoutResponse {
    private String username;
    private String tokenTimeLeft;

    // username만 받는 생성자
    public LogoutResponse(String username) {
        this.username = username;
    }
}