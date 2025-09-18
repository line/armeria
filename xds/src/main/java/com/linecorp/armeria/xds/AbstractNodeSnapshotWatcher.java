/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;

abstract class AbstractNodeSnapshotWatcher<T> implements SnapshotWatcher<T>, SafeCloseable {

    private boolean preClosed;

    @Override
    public final void snapshotUpdated(T newSnapshot) {
        if (preClosed) {
            return;
        }
        doSnapshotUpdated(newSnapshot);
    }

    @Override
    public final void onError(XdsType type, String resourceName, Status status) {
        if (preClosed) {
            return;
        }
        doOnError(type, resourceName, status);
    }

    @Override
    public final void onMissing(XdsType type, String resourceName) {
        if (preClosed) {
            return;
        }
        doOnMissing(type, resourceName);
    }

    final void preClose() {
        if (preClosed) {
            return;
        }
        preClosed = true;
        doPreClose();
    }

    @Override
    public final void close() {
        preClose();
        doClose();
    }

    abstract void doSnapshotUpdated(T newSnapshot);

    abstract void doOnError(XdsType type, String resourceName, Status status);

    abstract void doOnMissing(XdsType type, String resourceName);

    /**
     * Invoked before a new child node is created. This may be useful if a shared resource (metrics)
     * must be released before a new child node binds to the shared resource.
     */
    abstract void doPreClose();

    protected abstract void doClose();
}
