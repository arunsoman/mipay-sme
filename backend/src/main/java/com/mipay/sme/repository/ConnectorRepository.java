package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.ConnectorStatus;
import com.mipay.sme.domain.Enums.ConnectorType;
import com.mipay.sme.domain.ledger.Connector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorRepository extends JpaRepository<Connector, String> {

    List<Connector> findAllByOrderByConnectorType();

    List<Connector> findByConnectorType(ConnectorType connectorType);

    Optional<Connector> findFirstByConnectorTypeAndStatusOrderByCreatedAtDesc(ConnectorType connectorType, ConnectorStatus status);
}
