package org.ddcn41.ticketing_system.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.user.dto.UserDto;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    //  모든 유저 조회
    public List<UserDto> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    // 유저 생성
    public UserDto createUser(UserDto userDto) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = userDto.getPasswordHash();

        String passwordHash = encoder.encode(password);

        User user = User.builder()
                .username(userDto.getUsername())
                .email(userDto.getEmail())
                .name(userDto.getName())
                .passwordHash(passwordHash)
                .phone(userDto.getPhone())
                .role(userDto.getRole())
                .build();

        User savedUser = userRepository.save(user);
        return convertToDto(savedUser);
    }

    // 유저 삭제
    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new RuntimeException("User not found with id: " + userId);
        }
        userRepository.deleteById(userId);
    }

    // 유저 검색
    public List<UserDto> searchUsers(String username, User.Role role, User.Status status) {
        List<UserDto> result = getAllUsers();

        return result.stream()
                .filter(u -> username == null || username.trim().isEmpty() ||
                        u.getUsername().toLowerCase().contains(username.toLowerCase()))
                .filter(u -> role == null || u.getRole().equals(role))
                .filter(u -> status == null || u.getStatus().equals(status))
                .collect(Collectors.toList());
    }

    public String resolveUsernameFromEmailOrUsername(String usernameOrEmail) {
        if (usernameOrEmail.contains("@")) {
            // 이메일로 사용자 찾기
            User user = userRepository.findByEmail(usernameOrEmail)
                    .orElseThrow(() -> new UsernameNotFoundException("해당 이메일의 사용자를 찾을 수 없습니다: " + usernameOrEmail));
            return user.getUsername();
        } else {
            User user = userRepository.findByUsername(usernameOrEmail)
                    .orElseThrow(() -> new UsernameNotFoundException("해당 사용자명을 찾을 수 없습니다: " + usernameOrEmail));
            return user.getUsername();
        }
    }

    /**
     * 사용자 역할 정보 반환
     */
    public String getUserRole(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
        return user.getRole().name();
    }

    /**
     * 사용자 정보 조회
     */
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    /**
     * 이메일로 사용자 정보 조회
     */
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("해당 이메일의 사용자를 찾을 수 없습니다: " + email));
    }

    private UserDto convertToDto(User user) {
        return UserDto.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .name(user.getName())
                .passwordHash(user.getPasswordHash())
                .phone(user.getPhone())
                .role(user.getRole())
                .status(user.getStatus())
                .build();
    }

    @Transactional
    public User updateUserLoginTime(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));

        user.setLastLogin(LocalDateTime.now());
        return userRepository.save(user);
    }
}