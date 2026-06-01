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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.stream.SnapshotStream;

/**
 * A callback interface for receiving snapshot updates from a {@link SnapshotStream}.
 *
 * @param <T> the type of snapshot values received by this watcher
 */
@UnstableApi
@FunctionalInterface
public interface SnapshotWatcher<T> {

    /**
     * Invoked when a snapshot is updated or an error occurs.
     * Exactly one of {@code snapshot} or {@code error} will be non-null.
     *
     * @param snapshot the updated snapshot value, or {@code null} if an error occurred
     * @param error the error, or {@code null} if a snapshot was delivered
     */
    void onUpdate(@Nullable T snapshot, @Nullable Throwable error);
}
