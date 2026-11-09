package com.mipay.sme.domain.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** RTO 2h / RPO 0 restore-test evidence (X14, TC-SEC-03, UAT SB p.17). */
@Entity
@Table(name = "restore_tests")
@Getter @Setter
public class RestoreTest {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(name = "backup_id", length = 36, nullable = false)
    private String backupId;

    @Column(name = "tested_at")
    private Instant testedAt;

    @Column(name = "rto_achieved_minutes")
    private Integer rtoAchievedMinutes;

    @Column(name = "rpo_achieved_minutes")
    private Integer rpoAchievedMinutes;

    @Column(columnDefinition = "boolean default true")
    private Boolean ok = true;

    @Column(name = "tested_by", length = 36)
    private String testedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (testedAt == null) testedAt = Instant.now();
    }
}
