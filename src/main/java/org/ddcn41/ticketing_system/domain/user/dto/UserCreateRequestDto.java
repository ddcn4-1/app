package org.ddcn41.ticketing_system.domain.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.user.entity.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreateRequestDto {
    private Long userId;
    private String username;
    private String email;
    private String name;
    private String password;
    private String phone;
    private User.Role role;
    private User.Status status;
}
