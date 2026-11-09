package com.mipay.sme.domain.admin;

import com.mipay.sme.domain.Enums.TemplateChannel;
import com.mipay.sme.domain.Enums.TemplateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** SMS/WhatsApp/speaker templates (C40-C42, Eco SB p.8). Body tokens like {amount}. */
@Entity
@Table(name = "notification_templates")
@Getter @Setter
public class NotificationTemplate {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateChannel channel;

    @Column(name = "event_code", nullable = false)
    private String eventCode;

    @Column(nullable = false, columnDefinition = "varchar(500)")
    private String body;

    @Column(nullable = false)
    private String locale = "en";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateStatus status = TemplateStatus.ACTIVE;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
