/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.opengauss;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.doc.FixFor;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.kafka.KafkaCluster;
import io.debezium.pipeline.source.snapshot.incremental.AbstractIncrementalSnapshotTest;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.util.Collect;
import io.debezium.util.Testing;
import org.apache.kafka.connect.data.Struct;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class IncrementalSnapshotIT extends AbstractIncrementalSnapshotTest<OpengaussConnector> {

    private static final String TOPIC_NAME = "test_server.s1.a";

    private static final String SETUP_TABLES_STMT = "DROP SCHEMA IF EXISTS s1 CASCADE;" + "CREATE SCHEMA s1; "
            + "CREATE SCHEMA s2; " + "CREATE TABLE s1.a (pk SERIAL, aa integer, PRIMARY KEY(pk));"
            + "CREATE TABLE s1.b (pk SERIAL, aa integer, PRIMARY KEY(pk));"
            + "CREATE TABLE s1.a4 (pk1 integer, pk2 integer, pk3 integer, pk4 integer, aa integer, PRIMARY KEY(pk1, pk2, pk3, pk4));"
            + "CREATE TABLE s1.a42 (pk1 integer, pk2 integer, pk3 integer, pk4 integer, aa integer);"
            + "CREATE TABLE s1.anumeric (pk numeric, aa integer, PRIMARY KEY(pk));"
            + "CREATE TABLE s1.debezium_signal (id varchar(64), type varchar(32), data varchar(2048));"
            + "ALTER TABLE s1.debezium_signal REPLICA IDENTITY FULL;"
            + "CREATE TYPE enum_type AS ENUM ('UP', 'DOWN', 'LEFT', 'RIGHT', 'STORY');"
            + "CREATE TABLE s1.enumpk (pk enum_type, aa integer, PRIMARY KEY(pk));";

    @Before
    public void before() throws SQLException {
        TestHelper.dropAllSchemas();
        initializeConnectorTestFramework();

        TestHelper.dropDefaultReplicationSlot();
        TestHelper.execute(SETUP_TABLES_STMT);
    }

    @After
    public void after() {
        stopConnector();
        TestHelper.dropDefaultReplicationSlot();
        TestHelper.dropPublication();
    }

    protected Configuration.Builder config() {
        return TestHelper.defaultConfig()
                .with(OpengaussConnectorConfig.SNAPSHOT_MODE, OpengaussConnectorConfig.SnapshotMode.NO_DATA.getValue())
                .with(OpengaussConnectorConfig.DROP_SLOT_ON_STOP, Boolean.FALSE)
                .with(OpengaussConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(OpengaussConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .with(OpengaussConnectorConfig.SCHEMA_INCLUDE_LIST, "s1")
                .with(CommonConnectorConfig.SIGNAL_ENABLED_CHANNELS, "source")
                .with(CommonConnectorConfig.SIGNAL_POLL_INTERVAL_MS, 5)
                .with(RelationalDatabaseConnectorConfig.MSG_KEY_COLUMNS, "s1.a42:pk1,pk2,pk3,pk4")
                // DBZ-4272 required to allow dropping columns just before an incremental snapshot
                .with("database.autosave", "conservative");
    }

    @Override
    protected Configuration.Builder mutableConfig(boolean signalTableOnly, boolean storeOnlyCapturedDdl) {
        final String tableIncludeList;
        if (signalTableOnly) {
            tableIncludeList = "s1.b";
        } else {
            tableIncludeList = "s1.a,s1.b";
        }
        return TestHelper.defaultConfig()
                .with(OpengaussConnectorConfig.SNAPSHOT_MODE, OpengaussConnectorConfig.SnapshotMode.NO_DATA.getValue())
                .with(OpengaussConnectorConfig.DROP_SLOT_ON_STOP, Boolean.FALSE)
                .with(OpengaussConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(CommonConnectorConfig.SIGNAL_POLL_INTERVAL_MS, 5)
                .with(OpengaussConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .with(OpengaussConnectorConfig.SCHEMA_INCLUDE_LIST, "s1")
                .with(RelationalDatabaseConnectorConfig.MSG_KEY_COLUMNS, "s1.a42:pk1,pk2,pk3,pk4")
                .with(OpengaussConnectorConfig.DROP_SLOT_ON_STOP, false)
                .with(RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST, tableIncludeList)
                // DBZ-4272 required to allow dropping columns just before an incremental snapshot
                .with("database.autosave", "conservative");
    }

    @Override
    protected Class<OpengaussConnector> connectorClass() {
        return OpengaussConnector.class;
    }

    @Override
    protected JdbcConnection databaseConnection() {
        return TestHelper.create();
    }

    @Override
    protected String topicName() {
        return TOPIC_NAME;
    }

    @Override
    public List<String> topicNames() {
        return List.of(TOPIC_NAME, "test_server.s1.b");
    }

    @Override
    protected String tableName() {
        return "s1.a";
    }

    @Override
    protected String noPKTopicName() {
        return "test_server.s1.a42";
    }

    @Override
    protected String noPKTableName() {
        return "s1.a42";
    }

    @Override
    protected List<String> tableNames() {
        return List.of("s1.a", "s1.b");
    }

    @Override
    protected String signalTableName() {
        return "s1.debezium_signal";
    }

    @Override
    protected void waitForConnectorToStart() {
        super.waitForConnectorToStart();
        TestHelper.waitForDefaultReplicationSlotBeActive();
    }

    @Override
    protected String connector() {
        return "opengauss";
    }

    @Override
    protected String server() {
        return TestHelper.TEST_SERVER;
    }

    @Test
    @FixFor("DBZ-6481")
    public void insertsEnumPk() throws Exception {
        // Testing.Print.enable();
        final var enumValues = List.of("UP", "DOWN", "LEFT", "RIGHT", "STORY");

        try (JdbcConnection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            for (int i = 0; i < enumValues.size(); i++) {
                connection.executeWithoutCommitting(String.format("INSERT INTO %s (%s, aa) VALUES (%s, %s)",
                        "s1.enumpk", connection.quotedColumnIdString(pkFieldName()), "'" + enumValues.get(i) + "'", i));
            }
            connection.commit();
        }
        startConnector();

        sendAdHocSnapshotSignal("s1.enumpk");

        // SNAPSHOT signal, OPEN WINDOW signal + data + CLOSE WINDOW signal
        final var records = consumeRecordsByTopic(enumValues.size() + 3).allRecordsInOrder();
        for (int i = 0; i < enumValues.size(); i++) {
            var record = records.get(i + 2);
            assertThat(((Struct) record.key()).getString("pk")).isEqualTo(enumValues.get(i));
            assertThat(((Struct) record.value()).getStruct("after").getInt32("aa")).isEqualTo(i);
        }
    }

    @Test
    public void inserts4Pks() throws Exception {
        Testing.Print.enable();

        populate4PkTable();
        startConnector();

        sendAdHocSnapshotSignal("s1.a4");

        Thread.sleep(5000);
        try (JdbcConnection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            for (int i = 0; i < ROW_COUNT; i++) {
                final int id = i + ROW_COUNT + 1;
                final int pk1 = id / 1000;
                final int pk2 = (id / 100) % 10;
                final int pk3 = (id / 10) % 10;
                final int pk4 = id % 10;
                connection.executeWithoutCommitting(String.format("INSERT INTO %s (pk1, pk2, pk3, pk4, aa) VALUES (%s, %s, %s, %s, %s)",
                        "s1.a4",
                        pk1,
                        pk2,
                        pk3,
                        pk4,
                        i + ROW_COUNT));
            }
            connection.commit();
        }

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(
                expectedRecordCount,
                x -> true,
                k -> k.getInt32("pk1") * 1_000 + k.getInt32("pk2") * 100 + k.getInt32("pk3") * 10 + k.getInt32("pk4"),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.a4",
                null);
        for (int i = 0; i < expectedRecordCount; i++) {
            Assertions.assertThat(dbChanges).contains(Assertions.entry(i + 1, i));
        }
    }

    @Test
    public void insertsNumericPk() throws Exception {
        // Testing.Print.enable();

        try (final JdbcConnection connection = databaseConnection()) {
            populateTable(connection, "s1.anumeric");
        }
        startConnector();

        sendAdHocSnapshotSignal("s1.anumeric");

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(
                expectedRecordCount,
                x -> true,
                k -> VariableScaleDecimal.toLogical(k.getStruct("pk")).getWrappedValue().intValue(),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.anumeric",
                null);
        for (int i = 0; i < expectedRecordCount; i++) {
            Assertions.assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    protected void populate4PkTable() throws SQLException {
        try (final JdbcConnection connection = databaseConnection()) {
            populate4PkTable(connection, "s1.a4");
        }
    }
}
