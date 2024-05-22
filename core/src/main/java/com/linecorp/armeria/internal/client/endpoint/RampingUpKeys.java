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

    private static final AttributeKey<Long> endpointCreateTimestampNanos =
            AttributeKey.valueOf(RampingUpKeys.class, "getCreateTimestamp");

    public static long createTimestamp(Endpoint endpoint) {
        final Long createdTimestamp = endpoint.attr(endpointCreateTimestampNanos);
        checkState(createdTimestamp != null, "createdTimestamp doesn't exist for '%s'", endpoint);
        return createdTimestamp;
    }

    public static Endpoint withCreateTimestamp(Endpoint endpoint, long timestamp) {
        return endpoint.withAttr(endpointCreateTimestampNanos, timestamp);
    }

    public static boolean hasCreateTimestamp(Endpoint endpoint) {
        return endpoint.attr(endpointCreateTimestampNanos) != null;
    }

    private RampingUpKeys() {}
}
