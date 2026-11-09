package com.mipay.sme.repository;

import com.mipay.sme.domain.core.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
