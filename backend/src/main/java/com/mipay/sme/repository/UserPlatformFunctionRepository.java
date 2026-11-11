package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.UserPlatformFunction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UserPlatformFunctionRepository extends JpaRepository<UserPlatformFunction, UserPlatformFunction.PK> {

    List<UserPlatformFunction> findByUserId(String userId);

    List<UserPlatformFunction> findByUserIdIn(Collection<String> userIds);

    List<UserPlatformFunction> findByFunction(String function);

    boolean existsByUserIdAndFunction(String userId, String function);
}
