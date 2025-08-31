package com.meshiya.repository;

import com.meshiya.model.RegisteredUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegisteredUserRepository extends JpaRepository<RegisteredUser, Long> {
    
    Optional<RegisteredUser> findByUsername(String username);
    
    Optional<RegisteredUser> findByEmail(String email);
    
    Optional<RegisteredUser> findByUsernameOrEmail(String username, String email);
    
    boolean existsByUsername(String username);
    
    boolean existsByEmail(String email);
}