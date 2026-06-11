package com.otilm.core.dao.repository;

import com.otilm.api.model.client.connector.v2.ConnectorInterface;
import com.otilm.core.dao.entity.ConnectorInterfaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorInterfaceRepository extends JpaRepository<ConnectorInterfaceEntity, UUID> {

    Optional<ConnectorInterfaceEntity> findByConnectorUuidAndInterfaceCode(UUID connectorUuid, ConnectorInterface interfaceCode);

}
