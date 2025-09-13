package com.meshiya.repository;

import com.meshiya.model.RegisteredUser;
// Disabled for DynamoDB migration - JPA dependencies removed
// import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// DISABLED: JPA Repository - replaced with DynamoUserRepository
// @Repository
// public interface RegisteredUserRepository extends JpaRepository<RegisteredUser, Long> {
//     
//     Optional<RegisteredUser> findByUsername(String username);
//     
//     Optional<RegisteredUser> findByEmail(String email);
//     
//     Optional<RegisteredUser> findByUsernameOrEmail(String username, String email);
//     
//     boolean existsByUsername(String username);
//     
//     boolean existsByEmail(String email);
// }

// Legacy compatibility interface for DynamoDB migration
@Repository
public class RegisteredUserRepository {
    // This class is disabled - use DynamoUserRepository instead
    // Kept for compilation compatibility during migration
}