/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.opengauss.converters;

import io.debezium.connector.opengauss.Module;
import io.debezium.converters.recordandmetadata.RecordAndMetadata;
import io.debezium.converters.spi.CloudEventsMaker;
import io.debezium.converters.spi.CloudEventsProvider;
import io.debezium.converters.spi.RecordParser;
import io.debezium.converters.spi.SerializerType;

/**
 * An implementation of {@link CloudEventsProvider} for PostgreSQL.
 *
 * @author Chris Cranford
 */
public class OpengaussCloudEventsProvider implements CloudEventsProvider {
    @Override
    public String getName() {
        return Module.name();
    }

    @Override
    public RecordParser createParser(RecordAndMetadata recordAndMetadata) {
        return new OpengaussRecordParser(recordAndMetadata);
    }

    @Override
    public CloudEventsMaker createMaker(RecordParser parser, SerializerType contentType, String dataSchemaUriBase, String cloudEventsSchemaName) {
        return new OpengaussCloudEventsMaker(parser, contentType, dataSchemaUriBase, cloudEventsSchemaName);
    }
}
