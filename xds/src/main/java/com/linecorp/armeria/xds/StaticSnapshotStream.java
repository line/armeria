/*
 * Copyright 2026 LY Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;

final class StaticSnapshotStream<T> implements SnapshotStream<T> {

    private final @Nullable T value;
    private final @Nullable Throwable error;

    StaticSnapshotStream(@Nullable T value, @Nullable Throwable error) {
        if (value == null && error == null) {
            throw new IllegalArgumentException("Either value or error must be non-null");
        }
        this.value = value;
        this.error = error;
    }

    @Override
    public Subscription subscribe(SnapshotWatcher<? super T> watcher) {
        watcher.onUpdate(value, error);
        return Subscription.noop();
    }
}
