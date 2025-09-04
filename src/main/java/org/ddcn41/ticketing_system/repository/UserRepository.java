package org.ddcn41.ticketing_system.repository;

import java.util.Optional;

import org.ddcn41.ticketing_system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    // 이메일 로그인
    Optional<User> findByEmail(String email);

    // 회원가입 기능을 안 쓰더라도, 중복 체크가 필요하면 유지
//    boolean existsByUsername(String username);
}
