/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.opengauss.connection;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.debezium.DebeziumException;
import io.debezium.connector.opengauss.OpengaussConnectorConfig;
import io.debezium.connector.opengauss.OpengaussSchema;
import io.debezium.connector.opengauss.TypeRegistry;
import io.debezium.connector.opengauss.spi.SlotCreationResult;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.jdbc.JdbcConnectionException;
import io.debezium.relational.RelationalTableFilters;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;
import io.debezium.util.Metronome;
import org.apache.kafka.connect.errors.ConnectException;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.Math.toIntExact;

/**
 * Implementation of a {@link ReplicationConnection} for Postgresql. Note that replication connections in PG cannot execute
 * regular statements but only a limited number of replication-related commands.
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 * @author zhanghelong
 */
public class OpengaussReplicationConnection extends JdbcConnection implements ReplicationConnection {
    private static Logger LOGGER = LoggerFactory.getLogger(OpengaussReplicationConnection.class);

    private final String slotName;
    private final String publicationName;
    private final RelationalTableFilters tableFilter;
    private final OpengaussConnectorConfig.AutoCreateMode publicationAutocreateMode;
    private final OpengaussConnectorConfig.LogicalDecoder plugin;
    private final boolean dropSlotOnClose;
    private final OpengaussConnectorConfig originalConfig;
    private final Duration statusUpdateInterval;
    private final MessageDecoder messageDecoder;
    private final TypeRegistry typeRegistry;
    private final Properties streamParams;

    private Lsn defaultStartingPos;
    private SlotCreationResult slotCreationInfo;
    private boolean hasInitedSlot;

    /**
     * Creates a new replication connection with the given params.
     *
     * @param config                    the JDBC configuration for the connection; may not be null
     * @param slotName                  the name of the DB slot for logical replication; may not be null
     * @param publicationName           the name of the DB publication for logical replication; may not be null
     * @param tableFilter               the tables to watch of the DB publication for logical replication; may not be null
     * @param publicationAutocreateMode the mode for publication autocreation; may not be null
     * @param plugin                    decoder matching the server side plug-in used for streaming changes; may not be null
     * @param dropSlotOnClose           whether the replication slot should be dropped once the connection is closed
     * @param statusUpdateInterval      the interval at which the replication connection should periodically send status
     * @param jdbcConnection            general OpengaussSQL JDBC connection
     * @param typeRegistry              registry with PostgreSQL types
     * @param streamParams              additional parameters to pass to the replication stream
     * @param schema                    the schema; must not be null
     */
    private OpengaussReplicationConnection(OpengaussConnectorConfig config,
                                           String slotName,
                                           String publicationName,
                                           RelationalTableFilters tableFilter,
                                           OpengaussConnectorConfig.AutoCreateMode publicationAutocreateMode,
                                           OpengaussConnectorConfig.LogicalDecoder plugin,
                                           boolean dropSlotOnClose,
                                           Duration statusUpdateInterval,
                                           OpengaussConnection jdbcConnection,
                                           TypeRegistry typeRegistry,
                                           Properties streamParams,
                                           OpengaussSchema schema) {
        super(addDefaultSettings(config.getJdbcConfig()), OpengaussConnection.FACTORY, "\"", "\"");

        this.originalConfig = config;
        this.slotName = slotName;
        this.publicationName = publicationName;
        this.tableFilter = tableFilter;
        this.publicationAutocreateMode = publicationAutocreateMode;
        this.plugin = plugin;
        this.dropSlotOnClose = dropSlotOnClose;
        this.statusUpdateInterval = statusUpdateInterval;
        this.messageDecoder = plugin.messageDecoder(new MessageDecoderContext(config, schema));
        this.typeRegistry = typeRegistry;
        this.streamParams = streamParams;
        this.slotCreationInfo = null;
        this.hasInitedSlot = false;
    }

    private static JdbcConfiguration addDefaultSettings(JdbcConfiguration configuration) {
        // first copy the parent's default settings...
        // then set some additional replication specific settings
        return JdbcConfiguration.adapt(OpengaussConnection.addDefaultSettings(configuration)
                .edit()
                .with("replication", "database")
                .with("preferQueryMode", "simple") // replication protocol only supports simple query mode
                .build());
    }

    private ServerInfo.ReplicationSlot getSlotInfo() throws SQLException, InterruptedException {
        try (OpengaussConnection connection = new OpengaussConnection(originalConfig.getJdbcConfig())) {
            return connection.readReplicationSlotInfo(slotName, plugin.getOpengaussPluginName());
        }
    }

    protected void initPublication() {
        String createPublicationStmt;
        if (OpengaussConnectorConfig.LogicalDecoder.PGOUTPUT.equals(plugin)) {
            LOGGER.info("Initializing logical decoder publication");
            try {
                Connection conn = pgConnection();

                String selectPublication = String.format("SELECT puballtables FROM pg_publication WHERE pubname = '%s'", publicationName);
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(selectPublication)) {
                    if (!rs.next()) {
                        LOGGER.info("Creating new publication '{}' for plugin '{}'", publicationName, plugin);
                        switch (publicationAutocreateMode) {
                            case DISABLED:
                                throw new ConnectException("Publication autocreation is disabled, please create one and restart the connector.");
                            case ALL_TABLES:
                                createPublicationStmt = String.format("CREATE PUBLICATION %s FOR ALL TABLES;", publicationName);
                                LOGGER.info("Creating Publication with statement '{}'", createPublicationStmt);
                                // Publication doesn't exist, create it.
                                stmt.execute(createPublicationStmt);
                                break;
                            case FILTERED:
                                createOrUpdatePublicationModeFiltered(stmt, false);
                                break;
                        }
                    } else {
                        // Checking that publication can be altered
                        if (Objects.requireNonNull(publicationAutocreateMode) == OpengaussConnectorConfig.AutoCreateMode.FILTERED) {
                            boolean allTables = rs.getBoolean(1);
                            if (allTables) {
                                throw new DebeziumException(String.format(
                                        "A logical publication for all tables named '%s' for plugin '%s' and database '%s' " +
                                                "is already active on the server and can not be altered. " +
                                                "If you need to exclude some tables or include only specific subset, " +
                                                "please recreate the publication with necessary configuration " +
                                                "or let plugin recreate it by dropping existing publication. " +
                                                "Otherwise please change the 'publication.autocreate.mode' property to 'all_tables'.",
                                        publicationName, plugin, database()));
                            } else {
                                createOrUpdatePublicationModeFiltered(stmt, true);
                            }
                        } else {
                            LOGGER.trace(
                                    "A logical publication named '{}' for plugin '{}' and database '{}' is already active on the server " +
                                            "and will be used by the plugin",
                                    publicationName, plugin, database());
                        }
                    }
                }
                conn.close();
            } catch (SQLException e) {
                throw new JdbcConnectionException(e);
            }
        }
    }

    private void createOrUpdatePublicationModeFiltered(Statement stmt, boolean isUpdate) {
        String tableFilterString = null;
        String createOrUpdatePublicationStmt;
        try {
            Set<TableId> tablesToCapture = determineCapturedTables();
            tableFilterString = tablesToCapture.stream().map(TableId::toDoubleQuotedString).collect(Collectors.joining(", "));
            if (tableFilterString.isEmpty()) {
                throw new DebeziumException(String.format("No table filters found for filtered publication %s", publicationName));
            }
            createOrUpdatePublicationStmt = isUpdate ? String.format("ALTER PUBLICATION %s SET TABLE %s;", publicationName, tableFilterString)
                    : String.format("CREATE PUBLICATION %s FOR TABLE %s;", publicationName, tableFilterString);
            LOGGER.info(isUpdate ? "Updating Publication with statement '{}'" : "Creating Publication with statement '{}'", createOrUpdatePublicationStmt);
            stmt.execute(createOrUpdatePublicationStmt);
        } catch (Exception e) {
            throw new ConnectException(String.format("Unable to %s filtered publication %s for %s", isUpdate ? "update" : "create", publicationName, tableFilterString),
                    e);
        }
    }

    private Set<TableId> determineCapturedTables() throws Exception {
        Set<TableId> allTableIds = this.connect().readTableNames(pgConnection().getCatalog(), null, null, new String[] {"TABLE"});

        Set<TableId> capturedTables = new HashSet<>();

        for (TableId tableId : allTableIds) {
            if (tableFilter.dataCollectionFilter().isIncluded(tableId)) {
                LOGGER.trace("Adding table {} to the list of captured tables", tableId);
                capturedTables.add(tableId);
            } else {
                LOGGER.trace("Ignoring table {} as it's not included in the filter configuration", tableId);
            }
        }

        return capturedTables
                .stream()
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    protected void initReplicationSlot() throws SQLException, InterruptedException {
        ServerInfo.ReplicationSlot slotInfo = getSlotInfo();

        boolean shouldCreateSlot = ServerInfo.ReplicationSlot.INVALID == slotInfo;
        try {
            // there's no info for this plugin and slot so create a new slot
            if (shouldCreateSlot) {
                this.createReplicationSlot();
            }

            // replication connection does not support parsing of SQL statements so we need to create
            // the connection without executing on connect statements - see JDBC opt preferQueryMode=simple
            pgConnection();
            final String identifySystemStatement = "IDENTIFY_SYSTEM";
            LOGGER.debug("running '{}' to validate replication connection", identifySystemStatement);
            final Lsn xlogStart = queryAndMap(identifySystemStatement, rs -> {
                if (!rs.next()) {
                    throw new IllegalStateException("The DB connection is not a valid replication connection");
                }
                String xlogpos = rs.getString("xlogpos");
                LOGGER.debug("received latest xlogpos '{}'", xlogpos);
                return Lsn.valueOf(xlogpos);
            });

            if (slotCreationInfo != null) {
                this.defaultStartingPos = slotCreationInfo.startLsn();
            } else if (shouldCreateSlot || !slotInfo.hasValidFlushedLsn()) {
                // this is a new slot or we weren't able to read a valid flush LSN pos, so we always start from the xlog pos that was reported
                this.defaultStartingPos = xlogStart;
            } else {
                Lsn latestFlushedLsn = slotInfo.latestFlushedLsn();
                this.defaultStartingPos = latestFlushedLsn.compareTo(xlogStart) < 0 ? latestFlushedLsn : xlogStart;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("found previous flushed LSN '{}'", latestFlushedLsn);
                }
            }
            hasInitedSlot = true;
        } catch (SQLException e) {
            throw new JdbcConnectionException(e);
        }
    }

    private boolean useTemporarySlot() throws SQLException {
        return false;
    }

    /**
     * creating a replication connection and starting to stream involves a few steps:
     * 1. we create the connection and ensure that
     * a. the slot exists
     * b. the slot isn't currently being used
     * 2. we query to get our potential start position in the slot (lsn)
     * 3. we try and start streaming, depending on our options (such as in wal2json)
     * this may fail, which can result in the connection being killed and we need to start
     * the process over if we are using a temporary slot
     * 4. actually start the streamer
     * <p>
     * This method takes care of all of these and this method queries for a default starting position
     * If you know where you are starting from you should call {@link #startStreaming(Lsn, WalPositionLocator)}, this method
     * delegates to that method
     *
     * @return
     * @throws SQLException
     * @throws InterruptedException
     */
    @Override
    public ReplicationStream startStreaming(WalPositionLocator walPosition) throws SQLException, InterruptedException {
        return startStreaming(null, walPosition);
    }

    @Override
    public ReplicationStream startStreaming(Lsn offset, WalPositionLocator walPosition) throws SQLException, InterruptedException {
        initConnection();

        connect();
        if (offset == null || !offset.isValid()) {
            offset = defaultStartingPos;
        }
        Lsn lsn = offset;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("starting streaming from LSN '{}'", lsn);
        }

        int maxRetries = config().getInteger(OpengaussConnectorConfig.MAX_RETRIES);
        int delay = config().getInteger(OpengaussConnectorConfig.RETRY_DELAY_MS);
        int tryCount = 0;
        while (true) {
            try {
                return createReplicationStream(lsn, walPosition);
            } catch (Exception e) {
                String message = "Failed to start replication stream at " + lsn;
                if (++tryCount > maxRetries) {
                    if (e.getMessage().matches(".*replication slot .* is active.*")) {
                        message += "; when setting up multiple connectors for the same database host, please make sure to use a distinct replication slot name for each.";
                    }
                    throw new DebeziumException(message, e);
                } else {
                    LOGGER.warn(message + ", waiting for {} ms and retrying, attempt number {} over {}", delay, tryCount, maxRetries);
                    final Metronome metronome = Metronome.sleeper(Duration.ofMillis(delay), Clock.SYSTEM);
                    metronome.pause();
                }
            }
        }
    }

    @Override
    public void initConnection() throws SQLException, InterruptedException {
        // See https://docs-opengauss.osinfra.cn/en/docs/5.0.0/docs/DatabaseReference/settings-60.html
        // For pgoutput specifically, the publication must be created before the slot.
        initPublication();
        if (!hasInitedSlot) {
            initReplicationSlot();
        }
    }

    @Override
    public Optional<SlotCreationResult> createReplicationSlot() throws SQLException {
        // note that some of these options are only supported in Postgres 9.4+, additionally
        // the options are not yet exported by the jdbc api wrapper, therefore, we just do
        // this ourselves but eventually this should be moved back to the jdbc API
        // see https://github.com/pgjdbc/pgjdbc/issues/1305

        LOGGER.debug("Creating new replication slot '{}' for plugin '{}'", slotName, plugin);
        String tempPart = "";
        // Exported snapshots are supported in Postgres 9.4+
        boolean canExportSnapshot = pgConnection().haveMinimumServerVersion(ServerVersion.v9_4);
        if ((dropSlotOnClose) && !canExportSnapshot) {
            LOGGER.warn("A slot marked as temporary or with an exported snapshot was created, " +
                    "but not on a supported version of Postgres, ignoring!");
        }
        if (useTemporarySlot()) {
            tempPart = "TEMPORARY";
        }

        // See https://www.postgresql.org/docs/current/logical-replication-quick-setup.html
        // For pgoutput specifically, the publication must be created prior to the slot.
        initPublication();

        try (Statement stmt = pgConnection().createStatement()) {
            String createCommand = String.format(
                    "CREATE_REPLICATION_SLOT \"%s\" %s LOGICAL %s",
                    slotName,
                    tempPart,
                    plugin.getOpengaussPluginName());
            LOGGER.info("Creating replication slot with command {}", createCommand);
            stmt.execute(createCommand);
            // when we are in Postgres 9.4+, we can parse the slot creation info,
            // otherwise, it returns nothing
            if (canExportSnapshot) {
                this.slotCreationInfo = parseSlotCreation(stmt.getResultSet());
            }

            return Optional.ofNullable(slotCreationInfo);
        }
    }

    protected BaseConnection pgConnection() throws SQLException {
        return (BaseConnection) connection(false);
    }

    private SlotCreationResult parseSlotCreation(ResultSet rs) {
        try {
            if (rs.next()) {
                String slotName = rs.getString("slot_name");
                String startPoint = rs.getString("consistent_point");
                String snapName = rs.getString("snapshot_name");
                String pluginName = rs.getString("output_plugin");

                return new SlotCreationResult(slotName, startPoint, snapName, pluginName);
            } else {
                throw new ConnectException("No replication slot found");
            }
        } catch (SQLException ex) {
            throw new ConnectException("Unable to parse create_replication_slot response", ex);
        }
    }

    private ReplicationStream createReplicationStream(final Lsn startLsn, WalPositionLocator walPosition) throws SQLException, InterruptedException {
        PGReplicationStream s;

        try {
            try {
                s = startPgReplicationStream(startLsn,
                        plugin.forceRds()
                                ? messageDecoder::optionsWithoutMetadata
                                : messageDecoder::optionsWithMetadata);
                messageDecoder.setContainsMetadata(plugin.forceRds() ? false : true);
            } catch (PSQLException e) {
                LOGGER.debug("Could not register for streaming, retrying without optional options", e);

                // re-init the slot after a failed start of slot, as this
                // may have closed the slot
                if (useTemporarySlot()) {
                    initReplicationSlot();
                }

                s = startPgReplicationStream(startLsn, plugin.forceRds() ? messageDecoder::optionsWithoutMetadata : messageDecoder::optionsWithMetadata);
                messageDecoder.setContainsMetadata(plugin.forceRds() ? false : true);
            }
        } catch (PSQLException e) {
            if (e.getMessage().matches("(?s)ERROR: option .* is unknown.*")) {
                // It is possible we are connecting to an old wal2json plug-in
                LOGGER.warn("Could not register for streaming with metadata in messages, falling back to messages without metadata");

                // re-init the slot after a failed start of slot, as this
                // may have closed the slot
                if (useTemporarySlot()) {
                    initReplicationSlot();
                }

                s = startPgReplicationStream(startLsn, messageDecoder::optionsWithoutMetadata);
                messageDecoder.setContainsMetadata(false);
            } else if (e.getMessage().matches("(?s)ERROR: requested WAL segment .* has already been removed.*")) {
                LOGGER.error("Cannot rewind to last processed WAL position", e);
                throw new ConnectException(
                        "The offset to start reading from has been removed from the database write-ahead log. Create a new snapshot and consider setting of PostgreSQL parameter wal_keep_segments = 0.");
            } else {
                throw e;
            }
        }

        final PGReplicationStream stream = s;

        return new ReplicationStream() {

            private static final int CHECK_WARNINGS_AFTER_COUNT = 100;
            private final Metronome metronome = Metronome.sleeper(statusUpdateInterval, Clock.SYSTEM);
            private int warningCheckCounter = CHECK_WARNINGS_AFTER_COUNT;
            private ExecutorService keepAliveExecutor = null;
            private AtomicBoolean keepAliveRunning;
            // make sure this is volatile since multiple threads may be interested in this value
            private volatile Lsn lastReceivedLsn;

            @Override
            public void read(ReplicationMessageProcessor processor) throws SQLException, InterruptedException, JsonProcessingException {
                processWarnings(false);
                ByteBuffer read = stream.read();
                final Lsn lastReceiveLsn = Lsn.valueOf(stream.getLastReceiveLSN());
                LOGGER.trace("Streaming requested from LSN {}, received LSN {}", startLsn, lastReceiveLsn);
                if (messageDecoder.shouldMessageBeSkipped(read, lastReceiveLsn, startLsn, walPosition)) {
                    LOGGER.warn("LSN {} - LSN {} has been skipped", startLsn, lastReceiveLsn);
                    return;
                }
                deserializeMessages(read, processor);
            }

            @Override
            public boolean readPending(ReplicationMessageProcessor processor) throws SQLException, InterruptedException, JsonProcessingException {
                processWarnings(false);
                ByteBuffer read = stream.readPending();
                final Lsn lastReceiveLsn = Lsn.valueOf(stream.getLastReceiveLSN());
                LOGGER.trace("Streaming requested from LSN {}, received LSN {}", startLsn, lastReceiveLsn);

                if (read == null) {
                    return false;
                }

                if (messageDecoder.shouldMessageBeSkipped(read, lastReceiveLsn, startLsn, walPosition)) {
                    return true;
                }
                deserializeMessages(read, processor);
                return true;
            }

            private void deserializeMessages(ByteBuffer buffer, ReplicationMessageProcessor processor) throws SQLException, InterruptedException, JsonProcessingException {
                lastReceivedLsn = Lsn.valueOf(stream.getLastReceiveLSN());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Last LSN {}", lastReceivedLsn);
                }
                messageDecoder.processMessage(buffer, processor, typeRegistry);
            }

            @Override
            public void close() throws SQLException {
                processWarnings(true);
                try {
                    stream.close();
                } catch (Exception e) {
                    // FIXME: ignore cause: java.io.EOFException: EOF Exception, seems like a driver problem
                    if (!(e.getCause() instanceof EOFException)) {
                        throw e;
                    }
                }
            }

            @Override
            public void flushLsn(Lsn lsn) throws SQLException {
                doFlushLsn(lsn);
            }

            private void doFlushLsn(Lsn lsn) throws SQLException {
                stream.setFlushedLSN(lsn.asLogSequenceNumber());
                stream.setAppliedLSN(lsn.asLogSequenceNumber());

                stream.forceUpdateStatus();
            }

            @Override
            public Lsn lastReceivedLsn() {
                return lastReceivedLsn;
            }

            @Override
            public void startKeepAlive(ExecutorService service) {
                if (keepAliveExecutor == null) {
                    keepAliveExecutor = service;
                    keepAliveRunning = new AtomicBoolean(true);
                    keepAliveExecutor.submit(() -> {
                        while (keepAliveRunning.get()) {
                            try {
                                LOGGER.trace("Forcing status update with replication stream");
                                stream.forceUpdateStatus();
                                metronome.pause();
                            } catch (Exception exp) {
                                throw new RuntimeException("received unexpected exception will perform keep alive", exp);
                            }
                        }
                    });
                }
            }

            @Override
            public void stopKeepAlive() {
                if (keepAliveExecutor != null) {
                    keepAliveRunning.set(false);
                    keepAliveExecutor.shutdownNow();
                    keepAliveExecutor = null;
                }
            }

            private void processWarnings(final boolean forced) throws SQLException {
                if (--warningCheckCounter == 0 || forced) {
                    warningCheckCounter = CHECK_WARNINGS_AFTER_COUNT;
                    for (SQLWarning w = connection().getWarnings(); w != null; w = w.getNextWarning()) {
                        LOGGER.debug("Server-side message: '{}', state = {}, code = {}",
                                w.getMessage(), w.getSQLState(), w.getErrorCode());
                    }
                    connection().clearWarnings();
                }
            }

            @Override
            public Lsn startLsn() {
                return startLsn;
            }
        };
    }

    private PGReplicationStream startPgReplicationStream(final Lsn lsn,
                                                         BiFunction<ChainedLogicalStreamBuilder, Function<Integer, Boolean>, ChainedLogicalStreamBuilder> configurator)
            throws SQLException {
        assert lsn != null;
        ChainedLogicalStreamBuilder streamBuilder = pgConnection()
                .getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName("\"" + slotName + "\"")
                .withStartPosition(lsn.asLogSequenceNumber())
                .withSlotOptions(streamParams);
        streamBuilder = configurator.apply(streamBuilder, this::hasMinimumVersion);

        if (statusUpdateInterval != null && statusUpdateInterval.toMillis() > 0) {
            streamBuilder.withStatusInterval(toIntExact(statusUpdateInterval.toMillis()), TimeUnit.MILLISECONDS);
        }

        PGReplicationStream stream = streamBuilder.start();

        // TODO DBZ-508 get rid of this
        // Needed by tests when connections are opened and closed in a fast sequence
        try {
            Thread.sleep(10);
        } catch (Exception e) {
        }
        stream.forceUpdateStatus();
        return stream;
    }

    private Boolean hasMinimumVersion(int version) {
        try {
            return pgConnection().haveMinimumServerVersion(version);
        } catch (SQLException e) {
            throw new DebeziumException(e);
        }
    }

    @Override
    public synchronized void close() {
        close(true);
    }

    public synchronized void close(boolean dropSlot) {
        try {
            LOGGER.debug("Closing message decoder");
            messageDecoder.close();
        } catch (Throwable e) {
            LOGGER.error("Unexpected error while closing message decoder", e);
        }

        try {
            LOGGER.debug("Closing replication connection");
            super.close();
        } catch (Throwable e) {
            LOGGER.error("Unexpected error while closing Postgres connection", e);
        }
        if (dropSlotOnClose && dropSlot) {
            // we're dropping the replication slot via a regular - i.e. not a replication - connection
            try (OpengaussConnection connection = new OpengaussConnection(originalConfig.getJdbcConfig())) {
                connection.dropReplicationSlot(slotName);
                connection.dropPublication(publicationName);
            } catch (Throwable e) {
                LOGGER.error("Unexpected error while dropping replication slot", e);
            }
        }
    }

    @Override
    public void reconnect() throws SQLException {
        close(false);
        // Don't re-execute initial commands on reconnection
        connection(false);
    }

    protected static class ReplicationConnectionBuilder implements Builder {

        private final OpengaussConnectorConfig config;
        private String slotName = DEFAULT_SLOT_NAME;
        private String publicationName = DEFAULT_PUBLICATION_NAME;
        private RelationalTableFilters tableFilter;
        private OpengaussConnectorConfig.AutoCreateMode publicationAutocreateMode = OpengaussConnectorConfig.AutoCreateMode.ALL_TABLES;
        private OpengaussConnectorConfig.LogicalDecoder plugin = OpengaussConnectorConfig.LogicalDecoder.PGOUTPUT;
        private boolean dropSlotOnClose = DEFAULT_DROP_SLOT_ON_CLOSE;
        private Duration statusUpdateIntervalVal;
        private TypeRegistry typeRegistry;
        private OpengaussSchema schema;
        private Properties slotStreamParams = new Properties();
        private OpengaussConnection jdbcConnection;

        protected ReplicationConnectionBuilder(OpengaussConnectorConfig config) {
            assert config != null;
            this.config = config;
        }

        @Override
        public ReplicationConnectionBuilder withSlot(final String slotName) {
            assert slotName != null;
            this.slotName = slotName;
            return this;
        }

        @Override
        public Builder withPublication(String publicationName) {
            assert publicationName != null;
            this.publicationName = publicationName;
            return this;
        }

        @Override
        public Builder withTableFilter(RelationalTableFilters tableFilter) {
            assert tableFilter != null;
            this.tableFilter = tableFilter;
            return this;
        }

        @Override
        public Builder withPublicationAutocreateMode(OpengaussConnectorConfig.AutoCreateMode publicationAutocreateMode) {
            assert publicationName != null;
            this.publicationAutocreateMode = publicationAutocreateMode;
            return this;
        }

        @Override
        public ReplicationConnectionBuilder withPlugin(final OpengaussConnectorConfig.LogicalDecoder plugin) {
            assert plugin != null;
            this.plugin = plugin;
            return this;
        }

        @Override
        public ReplicationConnectionBuilder dropSlotOnClose(final boolean dropSlotOnClose) {
            this.dropSlotOnClose = dropSlotOnClose;
            return this;
        }

        @Override
        public ReplicationConnectionBuilder streamParams(final String slotStreamParams) {
            if (slotStreamParams != null && !slotStreamParams.isEmpty()) {
                this.slotStreamParams = new Properties();
                String[] paramsWithValues = slotStreamParams.split(";");
                for (String paramsWithValue : paramsWithValues) {
                    String[] paramAndValue = paramsWithValue.split("=");
                    if (paramAndValue.length == 2) {
                        this.slotStreamParams.setProperty(paramAndValue[0], paramAndValue[1]);
                    } else {
                        LOGGER.warn("The following STREAM_PARAMS value is invalid: {}", paramsWithValue);
                    }
                }
            }
            return this;
        }

        @Override
        public ReplicationConnectionBuilder statusUpdateInterval(final Duration statusUpdateInterval) {
            this.statusUpdateIntervalVal = statusUpdateInterval;
            return this;
        }

        @Override
        public Builder jdbcMetadataConnection(OpengaussConnection jdbcConnection) {
            this.jdbcConnection = jdbcConnection;
            return this;
        }

        @Override
        public ReplicationConnection build() {
            assert plugin != null : "Decoding plugin name is not set";
            return new OpengaussReplicationConnection(config, slotName, publicationName, tableFilter,
                    publicationAutocreateMode, plugin, dropSlotOnClose, statusUpdateIntervalVal,
                    jdbcConnection, typeRegistry, slotStreamParams, schema);
        }

        @Override
        public Builder withTypeRegistry(TypeRegistry typeRegistry) {
            this.typeRegistry = typeRegistry;
            return this;
        }

        @Override
        public Builder withSchema(OpengaussSchema schema) {
            this.schema = schema;
            return this;
        }
    }
}
