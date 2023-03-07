/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static java.util.Objects.requireNonNull;

final class ConnectionPoolMetrics {
    private final Counter connectionOpened;
    private final Counter connectionClosed;

    ConnectionPoolMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
        requireNonNull(parent, "parent");
        requireNonNull(idPrefix, "idPrefix");

        connectionOpened = parent.counter(idPrefix.name(), idPrefix.tags("state", "open"));
        connectionClosed = parent.counter(idPrefix.name(), idPrefix.tags("state", "close"));
    }

    void increaseConnOpened() {
        connectionOpened.increment();
    }

    void increaseConnClosed() {
        connectionClosed.increment();
    }
}
