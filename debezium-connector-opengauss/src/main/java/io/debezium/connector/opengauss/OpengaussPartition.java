/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.opengauss;

import static io.debezium.relational.RelationalDatabaseConnectorConfig.DATABASE_NAME;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.debezium.config.Configuration;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.AbstractPartition;
import io.debezium.util.Collect;

public class OpengaussPartition extends AbstractPartition implements Partition {
    private static final String SERVER_PARTITION_KEY = "server";

    private final String serverName;

    public OpengaussPartition(String serverName, String databaseName) {
        super(databaseName);
        this.serverName = serverName;
    }

    @Override
    public Map<String, String> getSourcePartition() {
        return Collect.hashMapOf(SERVER_PARTITION_KEY, serverName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final OpengaussPartition other = (OpengaussPartition) obj;
        return Objects.equals(serverName, other.serverName);
    }

    @Override
    public int hashCode() {
        return serverName.hashCode();
    }

    static class Provider implements Partition.Provider<OpengaussPartition> {
        private final OpengaussConnectorConfig connectorConfig;
        private final Configuration taskConfig;

        Provider(OpengaussConnectorConfig connectorConfig, Configuration taskConfig) {
            this.connectorConfig = connectorConfig;
            this.taskConfig = taskConfig;
        }

        @Override
        public Set<OpengaussPartition> getPartitions() {
            return Collections.singleton(new OpengaussPartition(
                    connectorConfig.getLogicalName(), taskConfig.getString(DATABASE_NAME.name())));
        }
    }
}
