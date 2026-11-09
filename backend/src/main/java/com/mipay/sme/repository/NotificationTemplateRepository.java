package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.admin.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {

    List<NotificationTemplate> findAllByOrderByEventCodeAsc();

    Optional<NotificationTemplate> findByEventCodeAndChannelAndStatus(String eventCode, Enums.TemplateChannel channel,
                                                                      Enums.TemplateStatus status);

    List<NotificationTemplate> findByChannelIn(List<Enums.TemplateChannel> channels);
}
