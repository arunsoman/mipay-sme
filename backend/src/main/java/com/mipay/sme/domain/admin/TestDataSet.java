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

/** Synthetic data sets used by the simulator (X15, BC p.17). */
@Entity
@Table(name = "test_data_sets")
@Getter @Setter
public class TestDataSet {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "varchar(1000)")
    private String description;

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "labelled_synthetic", columnDefinition = "boolean default true")
    private Boolean labelledSynthetic = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
        if (createdAt == null) createdAt = Instant.now();
    }
}
