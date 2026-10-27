package com.example.auth.repository;

import com.example.auth.entity.AuthRole;
import com.example.auth.entity.AuthUsr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface AuthUsrRepository extends JpaRepository<AuthUsr, Long> {
    Optional<AuthUsr> findByCustomerCustomerIdentifier(Long customerId);

    Optional<AuthUsr> findByEmail(String email);

    Optional<AuthUsr> findByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    List<AuthUsr> findAllByPhoneNumberIsNotNull();

    boolean existsByUsername(String username);

	Optional<AuthUsr> findByUsername(String username);

	List<AuthUsr> findByRoleRoleCodeIn(Collection<String> roleCodes);
}
