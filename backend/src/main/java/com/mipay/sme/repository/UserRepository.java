package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.UserRole;
import com.mipay.sme.domain.core.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findByOrgId(String orgId);

    List<User> findByOrgIdAndStatus(String orgId, com.mipay.sme.domain.Enums.UserStatus status);

    List<User> findByOrgIdAndOrgRole(String orgId, com.mipay.sme.domain.Enums.OrgMemberRole orgRole);

    List<User> findByOrgIdIn(List<String> orgIds);

    List<User> findByOrgIdIsNull();

    /**
     * Login identifier lookup [H6]: phone OR username must resolve to EXACTLY
     * one user; an ambiguous match is refused.
     */
    @Query("""
            select u from User u
            where u.phone = :identifier or u.username = :identifier
            """)
    List<User> findAllByIdentifier(@Param("identifier") String identifier);
}
