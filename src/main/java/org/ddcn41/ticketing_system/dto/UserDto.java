package org.ddcn41.ticketing_system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.entity.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private Long userId;
    private String username;
    private String passwordHash;
    private User.Role role;

    // TODO: 이후 필요에 따라 이메일, 전화번호 등 개인정보 추가
}
