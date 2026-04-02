/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.internal;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.primitives.Ints;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.TransportSocketSnapshot;

import io.netty.util.AttributeKey;

public final class XdsCommonUtil {

    public static final AttributeKey<TransportSocketSnapshot> TRANSPORT_SOCKET_SNAPSHOT_KEY =
            AttributeKey.valueOf(XdsCommonUtil.class, "TRANSPORT_SOCKET_SNAPSHOT_KEY");

    public static long durationToMillis(Duration duration, long defaultValue) {
        if (duration == Duration.getDefaultInstance()) {
            return defaultValue;
        }
        return durationToMillis(duration);
    }

    public static long durationToMillis(Duration duration) {
        final long millis = java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos()).toMillis();
        if (millis == 0) {
            return Long.MAX_VALUE;
        }
        return millis;
    }

    public static int uint32ValueToInt(UInt32Value uInt32Value, int defaultValue) {
        if (uInt32Value == UInt32Value.getDefaultInstance()) {
            return defaultValue;
        }
        return Ints.saturatedCast(uInt32Value.getValue());
    }

    @Nullable
    public static Integer simpleAtoi(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    public static Long simpleAtol(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isGrpcRequest(@Nullable HttpRequest req) {
        if (req == null) {
            return false;
        }
        final MediaType contentType = req.contentType();
        if (contentType == null) {
            return false;
        }
        final String subtype = contentType.subtype();
        return "grpc".equals(subtype) || subtype.startsWith("grpc+");
    }

    public static void setTlsParams(PreClientRequestContext ctx, Endpoint endpoint) {
        final TransportSocketSnapshot transportSocket =
                endpoint.attr(TRANSPORT_SOCKET_SNAPSHOT_KEY);
        checkArgument(transportSocket != null,
                      "TransportSocket not set for selected endpoint: %s", endpoint);
        final ClientTlsSpec clientTlsSpec = transportSocket.clientTlsSpec();
        if (clientTlsSpec == null) {
            ctx.setSessionProtocol(SessionProtocol.HTTP);
            return;
        }
        ctx.setSessionProtocol(SessionProtocol.HTTPS);
        ctx.setClientTlsSpec(clientTlsSpec);
    }

    private XdsCommonUtil() {}
}
