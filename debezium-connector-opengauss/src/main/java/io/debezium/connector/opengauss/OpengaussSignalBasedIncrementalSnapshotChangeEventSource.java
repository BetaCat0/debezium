/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.opengauss;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.opengauss.connection.OpengaussConnection;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.snapshot.incremental.SignalBasedIncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.DatabaseSchema;
import io.debezium.util.Clock;

/**
 * Custom PostgreSQL implementation of the {@link SignalBasedIncrementalSnapshotChangeEventSource} implementation
 * which performs an explicit schema refresh of a table prior to the incremental snapshot starting.
 *
 * @author Chris Cranford
 */
public class OpengaussSignalBasedIncrementalSnapshotChangeEventSource extends SignalBasedIncrementalSnapshotChangeEventSource<OpengaussPartition, TableId> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpengaussSignalBasedIncrementalSnapshotChangeEventSource.class);

    private final OpengaussConnection jdbcConnection;
    private final OpengaussSchema schema;

    public OpengaussSignalBasedIncrementalSnapshotChangeEventSource(RelationalDatabaseConnectorConfig config,
                                                                    JdbcConnection jdbcConnection,
                                                                    EventDispatcher<OpengaussPartition, TableId> dispatcher,
                                                                    DatabaseSchema<?> databaseSchema,
                                                                    Clock clock,
                                                                    SnapshotProgressListener<OpengaussPartition> progressListener,
                                                                    DataChangeEventListener<OpengaussPartition> dataChangeEventListener,
                                                                    NotificationService<OpengaussPartition, ? extends OffsetContext> notificationService) {
        super(config, jdbcConnection, dispatcher, databaseSchema, clock, progressListener, dataChangeEventListener, notificationService);
        this.jdbcConnection = (OpengaussConnection) jdbcConnection;
        this.schema = (OpengaussSchema) databaseSchema;
    }

    @Override
    protected Table refreshTableSchema(Table table) throws SQLException {
        LOGGER.debug("Refreshing table '{}' schema for incremental snapshot.", table.id());
        schema.refresh(jdbcConnection, table.id(), true);
        return schema.tableFor(table.id());
    }
}
