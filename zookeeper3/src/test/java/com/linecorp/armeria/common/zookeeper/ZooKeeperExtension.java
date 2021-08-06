/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.zookeeper;

import static com.google.common.base.Preconditions.checkState;

import java.time.Duration;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.testing.TemporaryFolder;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

import zookeeperjunit.CloseableZooKeeper;
import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;
import zookeeperjunit.ZooKeeperAssert;

public class ZooKeeperExtension extends AbstractAllOrEachExtension implements ZooKeeperAssert {

    private static final Duration duration = Duration.ofSeconds(10);
    private static final TemporaryFolder tempFolder = new TemporaryFolder();

    @Nullable
    private ZKInstance zkInstance;

    @Override
    protected void before(ExtensionContext context) throws Exception {
        zkInstance = ZKFactory.apply().withRootDir(tempFolder.create("zookeeper").toFile()).create();
        try {
            zkInstance.start().result(duration);
        } catch (Throwable ex) {
            throw new RuntimeException("Failed to start a ZooKeeper instance", ex);
        }
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        if (zkInstance != null) {
            zkInstance.stop().ready(duration);
            zkInstance = null;
        }
    }

    @Override
    public ZKInstance instance() {
        checkState(zkInstance != null, "ZKInstance not ready");
        return zkInstance;
    }

    public String connectString() {
        return instance().connectString().get();
    }

    public int port() {
        return instance().port().get();
    }

    @Override
    public CloseableZooKeeper connection() {
        // Try up to three times to reduce flakiness.
        Throwable lastCause = null;
        for (int i = 0; i < 3; i++) {
            try {
                return ZooKeeperAssert.super.connection();
            } catch (Throwable t) {
                lastCause = t;
            }
        }

        return Exceptions.throwUnsafely(lastCause);
    }
}
