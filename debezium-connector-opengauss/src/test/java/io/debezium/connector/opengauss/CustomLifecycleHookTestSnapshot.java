/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.opengauss;

import io.debezium.snapshot.mode.AlwaysSnapshotter;

public class CustomLifecycleHookTestSnapshot extends AlwaysSnapshotter {

    @Override
    public String name() {
        return CustomLifecycleHookTestSnapshot.class.getName();
    }

    private static final String INSERT_SNAPSHOT_COMPLETE_STATE = "INSERT INTO s1.lifecycle_state (hook, state) " +
            "VALUES ('snapshotComplete', 'complete');";

    @Override
    public void snapshotCompleted() {
        TestHelper.execute(INSERT_SNAPSHOT_COMPLETE_STATE);
    }
}
