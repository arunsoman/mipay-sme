package com.mipay.sme.repository;

import com.mipay.sme.domain.org.NotificationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationMessageRepository extends JpaRepository<NotificationMessage, String> {

    List<NotificationMessage> findTop100ByOrderByCreatedAtDesc();

    List<NotificationMessage> findByActivationTokenId(String activationTokenId);
}
