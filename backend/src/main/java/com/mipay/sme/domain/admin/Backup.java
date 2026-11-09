package com.mipay.sme.domain.admin;

import com.mipay.sme.domain.Enums.BackupStatus;
import com.mipay.sme.domain.Enums.BackupType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "backups")
@Getter @Setter
public class Backup {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "backup_type", nullable = false)
    private BackupType backupType;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BackupStatus status;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (startedAt == null) startedAt = Instant.now();
    }
}
