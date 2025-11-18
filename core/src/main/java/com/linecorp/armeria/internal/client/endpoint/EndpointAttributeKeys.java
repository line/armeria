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
import java.util.Objects;

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

    static {
        attributesCache.put(0, Attributes.of(HEALTHY_ATTR, false, DEGRADED_ATTR, false));
        attributesCache.put(1, Attributes.of(HEALTHY_ATTR, true, DEGRADED_ATTR, false));
        attributesCache.put(2, Attributes.of(HEALTHY_ATTR, false, DEGRADED_ATTR, true));
        attributesCache.put(3, Attributes.of(HEALTHY_ATTR, true, DEGRADED_ATTR, true));
    }

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
            key += 1;
        }
        if (degraded) {
            key += 2;
        }
        final Attributes attributes = attributesCache.get(key);
        assert attributes != null;
        return attributes;
    }

    public static boolean equalHealthCheckAttributes(Endpoint endpoint1, Endpoint endpoint2) {
        return Objects.equals(endpoint1.attr(HEALTHY_ATTR), endpoint2.attr(HEALTHY_ATTR)) &&
               Objects.equals(endpoint1.attr(DEGRADED_ATTR), endpoint2.attr(DEGRADED_ATTR));
    }

    private EndpointAttributeKeys() {}
}
