/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.opengauss.snapshot.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.debezium.annotation.ConnectorSpecific;
import io.debezium.connector.opengauss.OpengaussConnector;
import io.debezium.connector.opengauss.OpengaussConnectorConfig;
import io.debezium.snapshot.spi.SnapshotLock;

@ConnectorSpecific(connector = OpengaussConnector.class)
public class SharedSnapshotLock implements SnapshotLock {

    @Override
    public String name() {
        return OpengaussConnectorConfig.SnapshotLockingMode.SHARED.getValue();
    }

    @Override
    public void configure(Map<String, ?> properties) {

    }

    @Override
    public Optional<String> tableLockingStatement(Duration lockTimeout, String tableId) {

        return Optional.of(String.format("LOCK TABLE %s IN ACCESS SHARE MODE;", tableId));
    }
}
