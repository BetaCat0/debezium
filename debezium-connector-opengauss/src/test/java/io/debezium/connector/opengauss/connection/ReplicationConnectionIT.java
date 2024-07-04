/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.opengauss.connection;

import io.debezium.DebeziumException;
import io.debezium.connector.opengauss.TestHelper;
import io.debezium.connector.opengauss.junit.SkipTestDependingOnDecoderPluginNameRule;
import io.debezium.jdbc.JdbcConnection.ResultSetMapper;
import io.debezium.junit.logging.LogInterceptor;
import io.debezium.util.Clock;
import io.debezium.util.Metronome;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test for {@link ReplicationConnection},
 * Due to the limitations mentioned in <a href="https://docs-opengauss.osinfra.cn/en/docs/5.0.0/docs/DatabaseOMGuide/overview-100.html">Precautions</a>,
 * DDL statements and DML statements in the same transaction will not be replicated.
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 * @author zhanghelong
 */
public class ReplicationConnectionIT {

    @Rule
    public TestRule skip = new SkipTestDependingOnDecoderPluginNameRule();

    @Before
    public void before() throws Exception {
        TestHelper.dropAllSchemas();
        String statement = "SET search_path TO public;" +
                "CREATE SCHEMA IF NOT EXISTS public;" +
                "CREATE TABLE table_with_pk (a SERIAL, b VARCHAR(30), c TIMESTAMP NOT NULL, PRIMARY KEY(a, c));" +
                "CREATE TABLE table_without_pk (a SERIAL, b NUMERIC(5,2), c TEXT);";
        TestHelper.execute(statement);
    }

    @Test
    public void shouldCreateAndDropReplicationSlots() throws Exception {
        // create a replication connection which should be dropped once it's closed
        try (ReplicationConnection connection = TestHelper.createForReplication("test1", true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            assertNull(stream.lastReceivedLsn());
            stream.close();
        }
        // create a replication connection which should be dropped once it's closed
        try (ReplicationConnection connection = TestHelper.createForReplication("test2", true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            assertNull(stream.lastReceivedLsn());
            stream.close();
        }
    }

    @Test(expected = DebeziumException.class)
    public void shouldNotAllowMultipleReplicationSlotsOnTheSameDBSlotAndPlugin() throws Exception {
        LogInterceptor interceptor = new LogInterceptor(OpengaussReplicationConnection.class);
        // create a replication connection which should be dropped once it's closed
        try (ReplicationConnection conn1 = TestHelper.createForReplication("test1", true)) {
            conn1.startStreaming(new WalPositionLocator());
            try (ReplicationConnection conn2 = TestHelper.createForReplication("test1", false)) {
                conn2.startStreaming(new WalPositionLocator());
                fail("Should not be able to create 2 replication connections on the same db, plugin and slot");
            } catch (Exception e) {
                assertTrue(interceptor.containsWarnMessage("and retrying, attempt number 2 over 2"));
                throw e;
            }
        }
    }

    @Test
    public void shouldCloseConnectionOnInvalidSlotName() throws Exception {
        final int closeRetries = 60;
        final int waitPeriod = 2_000;
        final String slotQuery = "select count(*) from pg_stat_replication where state = 'startup'";
        final ResultSetMapper<Integer> slotQueryMapper = rs -> {
            rs.next();
            return rs.getInt(1);
        };
        final int slotsBefore;

        try (OpengaussConnection connection = TestHelper.create()) {
            slotsBefore = connection.queryAndMap(slotQuery, slotQueryMapper);
        }

        try (ReplicationConnection conn1 = TestHelper.createForReplication("test1-#$%#$", true)) {
            conn1.startStreaming(new WalPositionLocator());
            fail("Invalid slot name should fail");
        } catch (Exception e) {
            try (OpengaussConnection connection = TestHelper.create()) {
                final int slotsAfter = connection.queryAndMap(slotQuery, slotQueryMapper);
                for (int retry = 1; retry <= closeRetries; retry++) {
                    if (slotsAfter <= slotsBefore) {
                        break;
                    }
                    if (retry == closeRetries) {
                        Assert.fail("Connection should not be active");
                    }
                    Thread.sleep(waitPeriod);
                }
            }
        }
    }

    @Test
    public void shouldReceiveAndDecodeIndividualChanges() throws Exception {
        // create a replication connection which should be dropped once it's closed
        try (ReplicationConnection connection = TestHelper.createForReplication("test", true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator()); // this creates the replication slot
            int expectedMessages = insertLargeTestData();
            expectedMessagesFromStream(stream, expectedMessages);
        }
    }

    @Test
    public void shouldReceiveSameChangesIfNotFlushed() throws Exception {
        // don't drop the replication slot once this is finished
        String slotName = "test";
        int receivedMessagesCount = startInsertStop(slotName, null);

        // create a new replication connection with the same slot and check that without the LSN having been flushed,
        // we'll get back the same message again from before
        try (ReplicationConnection connection = TestHelper.createForReplication(slotName, true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator()); // this should receive the same message as before since we haven't flushed
            expectedMessagesFromStream(stream, receivedMessagesCount);
        }
    }

    @Test
    public void shouldNotReceiveSameChangesIfFlushed() throws Exception {
        // don't drop the replication slot once this is finished
        String slotName = "test";
        startInsertStop(slotName, this::flushLsn);

        // create a new replication connection with the same slot and check that we don't get back the same changes that we've
        // flushed
        try (ReplicationConnection connection = TestHelper.createForReplication(slotName, true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            // even when flushing the last received location, the server will send back the last record after reconnecting, not sure why that is...
            expectedMessagesFromStream(stream, 0);
        }
    }

    @Test
    public void shouldReceiveMissedChangesWhileDown() throws Exception {
        String slotName = "test";
        startInsertStop(slotName, this::flushLsn);

        // run some more SQL while the slot is stopped
        // this deletes 2 entries so each of them will have a message
        TestHelper.execute("DELETE FROM table_with_pk WHERE a < 3;");
        int additionalMessages = 2;

        // create a new replication connection with the same slot and check that we get the additional messages
        try (ReplicationConnection connection = TestHelper.createForReplication(slotName, true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            expectedMessagesFromStream(stream, additionalMessages);
        }
    }

    @Test
    public void shouldResumeFromLastReceivedLSN() throws Exception {
        String slotName = "test";
        AtomicReference<Lsn> lastReceivedLsn = new AtomicReference<>();
        startInsertStop(slotName, stream -> lastReceivedLsn.compareAndSet(null, stream.lastReceivedLsn()));
        assertTrue(lastReceivedLsn.get().isValid());

        // resume replication from the last received LSN and don't expect anything else
        try (ReplicationConnection connection = TestHelper.createForReplication(slotName, true)) {
            ReplicationStream stream = connection.startStreaming(lastReceivedLsn.get(), new WalPositionLocator());
            expectedMessagesFromStream(stream, 0);
        }
    }

    @Test
    public void shouldTolerateInvalidLSNValues() throws Exception {
        String slotName = "test";
        startInsertStop(slotName, null);

        // resume replication from the last received LSN and don't expect anything else
        try (ReplicationConnection connection = TestHelper.createForReplication(slotName, true)) {
            ReplicationStream stream = connection.startStreaming(Lsn.valueOf(Long.MAX_VALUE), new WalPositionLocator());
            expectedMessagesFromStream(stream, 0);
            // this deletes 2 entries so each of them will have a message
            TestHelper.execute("DELETE FROM table_with_pk WHERE a < 3;");
            // don't expect any messages because we've started stream from a very big (i.e. the end) position
            expectedMessagesFromStream(stream, 0);
        }
    }

    // Limitation of Opengauss, see https://docs-opengauss.osinfra.cn/en/docs/5.0.0/docs/DatabaseOMGuide/overview-100.html
    @Test
    public void shouldNotReceiveMessageAfterDDLTransactionCommit() throws Exception {
        try (ReplicationConnection connection = TestHelper.createForReplication("test", true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            TestHelper.execute(
                    "DROP TABLE IF EXISTS table_with_pk;",
                    "DROP TABLE IF EXISTS table_without_pk;",
                    "CREATE TABLE table_with_pk (a SERIAL, b VARCHAR(30), c TIMESTAMP NOT NULL, PRIMARY KEY(a, c));",
                    "CREATE TABLE table_without_pk (a SERIAL, b NUMERIC(5,2), c TEXT);",
                    "INSERT INTO table_with_pk (b, c) VALUES('val1', now());",
                    "INSERT INTO table_with_pk (b, c) VALUES('val2', now());"
            );
            expectedMessagesFromStream(stream, 0);
        }
    }

    @Test
    public void shouldNotReceiveMessagesOnTransactionRollback() throws Exception {
        try (ReplicationConnection connection = TestHelper.createForReplication("test", true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            String statement = "DROP TABLE IF EXISTS table_with_pk;" +
                    "CREATE TABLE table_with_pk (a SERIAL, b VARCHAR(30), c TIMESTAMP NOT NULL, PRIMARY KEY(a, c));" +
                    "INSERT INTO table_with_pk (b, c) VALUES('val1', now()); " +
                    "ROLLBACK;";
            TestHelper.execute(statement);
            expectedMessagesFromStream(stream, 0);
        }
    }

    @Test
    public void shouldGeneratesEventsForMultipleSchemas() throws Exception {
        try (ReplicationConnection connection = TestHelper.createForReplication("test", true)) {
            ReplicationStream stream = connection.startStreaming(new WalPositionLocator());
            TestHelper.execute(
                    "CREATE SCHEMA schema1;" +
                            "CREATE SCHEMA schema2;" +
                            "DROP TABLE IF EXISTS schema1.table;" +
                            "DROP TABLE IF EXISTS schema2.table;" +
                            "CREATE TABLE schema1.table (a SERIAL, b VARCHAR(30), c TIMESTAMP NOT NULL, PRIMARY KEY(a, c));" +
                            "CREATE TABLE schema2.table (a SERIAL, b VARCHAR(30), c TIMESTAMP NOT NULL, PRIMARY KEY(a, c));"
            );
            TestHelper.execute(
                    "INSERT INTO schema1.table (b, c) VALUES('Value for schema1', now());" +
                            "INSERT INTO schema2.table (b, c) VALUES('Value for schema2', now());"
            );
            expectedMessagesFromStream(stream, 2);
        }
    }

    private void flushLsn(ReplicationStream stream) {
        try {
            stream.flushLsn(stream.lastReceivedLsn());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int startInsertStop(String slotName, Consumer<ReplicationStream> streamProcessor) throws Exception {
        // start a replication connection, insert some data and stop without dropping the slot....
        int expectedMessageCount;
        try (ReplicationConnection connection = TestHelper.createForReplication(slotName, false)) {
            try {
                ReplicationStream stream = connection.startStreaming(new WalPositionLocator()); // this creates the replication slot
                expectedMessageCount = insertSmallTestData();
                expectedMessagesFromStream(stream, expectedMessageCount);
                if (streamProcessor != null) {
                    streamProcessor.accept(stream);
                }
            } catch (Throwable t) {
                // make sure we always drop the slot if something fails - note the connection was created with the drop on close
                // set to false
                try (OpengaussConnection conn = TestHelper.create()) {
                    conn.dropReplicationSlot(slotName);
                }
                throw t;
            }
        }
        // wait a bit to make sure the replication connection has been stopped
        Thread.sleep(100);
        return expectedMessageCount;
    }

    private List<ReplicationMessage> expectedMessagesFromStream(ReplicationStream stream,
                                                                int expectedMessages)
            throws Exception {
        List<ReplicationMessage> actualMessages = new ArrayList<>();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Semaphore latch = new Semaphore(0);
        Metronome metronome = Metronome.sleeper(Duration.ofMillis(50), Clock.SYSTEM);
        Future<?> result = executorService.submit(() -> {
            while (!Thread.interrupted()) {
                for (; ; ) {
                    List<ReplicationMessage> message = new ArrayList<>();
                    stream.read(x -> {
                        // DBZ-2435 Explicitly remove transaction messages
                        // This helps situations where Pgoutput emits empty begin/commit blocks that can lead to
                        // inconsistent behavior with tests checking for replication stream state.
                        if (!x.isTransactionalMessage()) {
                            message.add(x);
                        }
                    });
                    if (message.isEmpty()) {
                        break;
                    }
                    actualMessages.addAll(message);
                    latch.release(message.size());
                }
                metronome.pause();
            }
            return null;
        });

        try {
            if (!latch.tryAcquire(expectedMessages, TestHelper.waitTimeForRecords() * 5, TimeUnit.SECONDS)) {
                result.cancel(true);
                fail("expected " + expectedMessages + " messages, but read only " + actualMessages.size());
            }
        } finally {
            executorService.shutdownNow();
        }
        return actualMessages;
    }

    private int insertSmallTestData() throws Exception {
        String statement = "INSERT INTO table_with_pk (b, c) VALUES('Backup and Restore', now());" +
                "INSERT INTO table_with_pk (b, c) VALUES('Tuning', now());";
        TestHelper.execute(statement);
        // we expect 2 messages from the above
        return 2;
    }

    private int insertLargeTestData() throws Exception {
        // Opengauss can't update table if it does not have a replica identity and publishes updates,
        // so we have to set REPLICA IDENTITY FULL for table_without_pk
        TestHelper.execute("ALTER TABLE table_without_pk REPLICA IDENTITY FULL;");
        String statement = "INSERT INTO table_with_pk (b, c) VALUES('Backup and Restore', now());" +
                "INSERT INTO table_with_pk (b, c) VALUES('Tuning', now());" +
                "DELETE FROM table_with_pk WHERE a < 3;" + // deletes 2 records
                "INSERT INTO table_without_pk (b,c) VALUES (1, 'Foo');" +
                "UPDATE table_without_pk SET c = 'Bar' WHERE c = 'Foo';" +
                "UPDATE table_without_pk SET c = 'Baz' WHERE c = 'Bar';" +
                "DELETE FROM table_without_pk WHERE c = 'Baz';";
        TestHelper.execute(statement);
        return 8;
    }
}
