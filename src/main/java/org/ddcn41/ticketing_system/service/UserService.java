package org.ddcn41.ticketing_system.service;

import org.ddcn41.ticketing_system.entity.User;
import org.ddcn41.ticketing_system.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
}