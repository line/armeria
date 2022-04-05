/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.stream;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link FixedStreamMessage} that publishes no objects, just a close event.
 */
@UnstableApi
public class EmptyFixedStreamMessage<T> extends FixedStreamMessage<T> {

    @Override
    public final boolean isEmpty() {
        return true;
    }

    @Override
    public long demand() {
        return 0;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        // Empty streams have no objects to clean.
    }

    @Override
    protected final List<T> drainAll(boolean withPooledObjects) {
        return ImmutableList.of();
    }

    @Override
    public void request(long n) {}
}
