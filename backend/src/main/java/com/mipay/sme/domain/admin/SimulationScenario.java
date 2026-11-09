package com.mipay.sme.domain.admin;

import com.mipay.sme.domain.Enums.ConnectorType;
import com.mipay.sme.domain.Enums.SimAction;
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

/** Simulation control panel / scenario runner definition (E31, E33, UAT SB p.16). */
@Entity
@Table(name = "simulation_scenarios")
@Getter @Setter
public class SimulationScenario {

    @Id
    @Column(name = "id", length = 36, unique = true)
    private String id;

    @Column(nullable = false)
    private String name;          // Scene 1 ... Scene 8

    @Column(columnDefinition = "varchar(500)")
    private String description;

    /** ConnectorType hint or NULL for platform scenes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "connector_type")
    private ConnectorType connectorType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimAction action;

    @Column(columnDefinition = "varchar(1000)")
    private String payload;

    @Column(name = "is_labelled", nullable = false, columnDefinition = "boolean default true")
    private Boolean isLabelled = true;

    @Column(columnDefinition = "boolean default true")
    private Boolean enabled = true;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().toLowerCase();
    }
}
