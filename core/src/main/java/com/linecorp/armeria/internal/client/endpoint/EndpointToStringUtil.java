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

import java.util.List;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

public final class EndpointToStringUtil {

    public static String toShortString(List<Endpoint> endpoints) {
        try (TemporaryThreadLocals acquired = TemporaryThreadLocals.acquire()) {
            final StringBuilder builder = acquired.stringBuilder();
            builder.append('[');
            for (int i = 0; i < endpoints.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                final Endpoint endpoint = endpoints.get(i);
                toShortString(builder, endpoint);
            }
            builder.append(']');
            return builder.toString();
        }
    }

    public static String toShortString(Endpoint endpoint) {
        try (TemporaryThreadLocals acquired = TemporaryThreadLocals.acquire()) {
            final StringBuilder builder = acquired.stringBuilder();
            toShortString(builder, endpoint);
            return builder.toString();
        }
    }

    private static void toShortString(StringBuilder builder, Endpoint endpoint) {
        builder.append(endpoint.host());
        if (endpoint.hasIpAddr() && !endpoint.isIpAddrOnly()) {
            builder.append('/').append(endpoint.ipAddr());
        }
        if (endpoint.hasPort()) {
            builder.append(':').append(endpoint.port());
        }
        builder.append(" (weight: ").append(endpoint.weight()).append(')');
    }

    private EndpointToStringUtil() {}
}
