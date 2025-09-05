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
public class UserDto {
    private Long userId;
    private String username;
    private String email;
    private String name;
    private String passwordHash;
    private String phone;
    private User.Role role;
}
