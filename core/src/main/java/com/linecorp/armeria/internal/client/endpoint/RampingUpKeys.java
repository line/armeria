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

package com.linecorp.armeria.internal.client.endpoint;

import static com.google.common.base.Preconditions.checkState;

import com.linecorp.armeria.client.Endpoint;

import io.netty.util.AttributeKey;

public final class RampingUpKeys {

    private static final AttributeKey<Long> CREATED_AT_NANOS_KEY =
            AttributeKey.valueOf(RampingUpKeys.class, "createdAtNanos");

    public static long createdAtNanos(Endpoint endpoint) {
        final Long createdAtNanos = endpoint.attr(CREATED_AT_NANOS_KEY);
        checkState(createdAtNanos != null, "createdAtNanos doesn't exist for '%s'", endpoint);
        return createdAtNanos;
    }

    public static Endpoint withCreatedAtNanos(Endpoint endpoint, long timestampNanos) {
        return endpoint.withAttr(CREATED_AT_NANOS_KEY, timestampNanos);
    }

    public static boolean hasCreatedAtNanos(Endpoint endpoint) {
        return endpoint.attr(CREATED_AT_NANOS_KEY) != null;
    }

    private RampingUpKeys() {}
}
