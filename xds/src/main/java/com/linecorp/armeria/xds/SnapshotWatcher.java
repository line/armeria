/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Status;

/**
 * A watcher implementation which waits for updates on an xDS snapshot.
 */
@UnstableApi
@FunctionalInterface
public interface SnapshotWatcher<T> {

    /**
     * Invoked when a full snapshot is updated.
     */
    void snapshotUpdated(T newSnapshot);

    /**
     * Invoked when a resource is deemed not to exist. This can either be due to
     * a timeout on a watch, or the xDS control plane explicitly signalling a resource is missing.
     */
    default void onMissing(XdsType type, String resourceName) {}

    /**
     * Invoked when an unexpected error occurs while processing a resource.
     */
    default void onError(XdsType type, Status status) {}
}
