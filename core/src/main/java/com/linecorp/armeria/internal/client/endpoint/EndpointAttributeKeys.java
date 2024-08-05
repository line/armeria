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

import java.util.Map;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public final class EndpointAttributeKeys {

    public static final AttributeKey<Long> CREATED_AT_NANOS_KEY =
            AttributeKey.valueOf(EndpointAttributeKeys.class, "createdAtNanos");
    public static final AttributeKey<Boolean> HEALTHY_ATTR =
            AttributeKey.valueOf(EndpointAttributeKeys.class, "HEALTHY");
    public static final AttributeKey<Boolean> DEGRADED_ATTR =
            AttributeKey.valueOf(EndpointAttributeKeys.class, "DEGRADED");

    private static final Map<Integer, Attributes> attributesCache = new Int2ObjectOpenHashMap<>();

    public static long createdAtNanos(Endpoint endpoint) {
        final Long createdAtNanos = endpoint.attr(CREATED_AT_NANOS_KEY);
        checkState(createdAtNanos != null, "createdAtNanos doesn't exist for '%s'", endpoint);
        return createdAtNanos;
    }

    public static boolean hasCreatedAtNanos(Endpoint endpoint) {
        return endpoint.attr(CREATED_AT_NANOS_KEY) != null;
    }

    @Nullable
    public static Boolean healthy(Endpoint endpoint) {
        return endpoint.attr(HEALTHY_ATTR);
    }

    @Nullable
    public static Boolean degraded(Endpoint endpoint) {
        return endpoint.attr(DEGRADED_ATTR);
    }

    public static Attributes healthCheckAttributes(boolean healthy, boolean degraded) {
        int key = 0;
        if (healthy) {
            key |= 1;
        }
        if (degraded) {
            key |= 1 << 1;
        }
        return attributesCache.computeIfAbsent(key, ignored -> Attributes.of(HEALTHY_ATTR, healthy,
                                                                             DEGRADED_ATTR, degraded));
    }

    private EndpointAttributeKeys() {}
}
