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

package com.linecorp.armeria.server.observation;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.net.InetSocketAddress;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.observation.HttpServiceObservationDocumentation.HighCardinalityKeys;
import com.linecorp.armeria.server.observation.HttpServiceObservationDocumentation.LowCardinalityKeys;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationConvention;

final class DefaultServiceObservationConvention implements ObservationConvention<ServiceObservationContext> {

    static final DefaultServiceObservationConvention INSTANCE =
            new DefaultServiceObservationConvention();

    @Override
    public KeyValues getLowCardinalityKeyValues(ServiceObservationContext context) {
        final ServiceRequestContext ctx = context.requestContext();
        int expectedSize = 1;
        KeyValue protocol = null;
        KeyValue serializationFormat = null;
        KeyValue statusCode = null;
        if (context.getResponse() != null) {
            final RequestLog log = ctx.log().ensureComplete();
            protocol = LowCardinalityKeys.HTTP_PROTOCOL.withValue(protocol(log));
            statusCode = LowCardinalityKeys.STATUS_CODE
                    .withValue(log.responseStatus().codeAsText());
            expectedSize = 3;
            final String serFmt = serializationFormat(log);
            if (serFmt != null) {
                expectedSize = 4;
                serializationFormat = LowCardinalityKeys.HTTP_SERIALIZATION_FORMAT.withValue(serFmt);
            }
        }
        final ImmutableList.Builder<KeyValue> builder = ImmutableList.builderWithExpectedSize(expectedSize);
        builder.add(LowCardinalityKeys.HTTP_METHOD.withValue(ctx.method().name()));
        addIfNotNull(protocol, builder);
        addIfNotNull(statusCode, builder);
        addIfNotNull(serializationFormat, builder);
        return KeyValues.of(builder.build());
    }

    private void addIfNotNull(@Nullable KeyValue keyValue, ImmutableList.Builder<KeyValue> builder) {
        if (keyValue != null) {
            builder.add(keyValue);
        }
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ServiceObservationContext context) {
        final ServiceRequestContext ctx = context.requestContext();
        int expectedSize = 3;
        KeyValue addressRemote = null;
        KeyValue addressLocal = null;
        KeyValue error = null;
        if (context.getResponse() != null) {
            final RequestLog log = ctx.log().ensureComplete();
            final InetSocketAddress raddr = ctx.remoteAddress();
            if (raddr != null) {
                expectedSize = expectedSize + 1;
                addressRemote = HighCardinalityKeys.ADDRESS_REMOTE.withValue(raddr.toString());
            }
            final InetSocketAddress laddr = ctx.localAddress();
            if (laddr != null) {
                expectedSize = expectedSize + 1;
                addressLocal = HighCardinalityKeys.ADDRESS_LOCAL.withValue(laddr.toString());
            }

            final Throwable responseCause = log.responseCause();
            if (responseCause != null) {
                expectedSize = expectedSize + 1;
                error = HighCardinalityKeys.ERROR.withValue(responseCause.toString());
            } else if (log.responseStatus().isError()) {
                expectedSize = expectedSize + 1;
                error = HighCardinalityKeys.ERROR.withValue(log.responseStatus().codeAsText());
            }
        }
        final ImmutableList.Builder<KeyValue> builder = ImmutableList.builderWithExpectedSize(expectedSize);
        builder.add(HighCardinalityKeys.HTTP_PATH.withValue(ctx.path()),
                    HighCardinalityKeys.HTTP_HOST.withValue(firstNonNull(context.httpRequest().authority(),
                                                                         "UNKNOWN")),
                    HighCardinalityKeys.HTTP_URL.withValue(ctx.uri().toString()));
        addIfNotNull(addressRemote, builder);
        addIfNotNull(addressLocal, builder);
        addIfNotNull(error, builder);
        return KeyValues.of(builder.build());
    }

    /**
     * Returns the {@link SessionProtocol#uriText()} of the {@link RequestLog}.
     */
    private static String protocol(RequestLog requestLog) {
        return requestLog.sessionProtocol().uriText();
    }

    /**
     * Returns the {@link SerializationFormat#uriText()} if it's not {@link SerializationFormat#NONE}.
     */
    @Nullable
    private static String serializationFormat(RequestLog requestLog) {
        final SerializationFormat serFmt = requestLog.serializationFormat();
        return serFmt == SerializationFormat.NONE ? null : serFmt.uriText();
    }

    @Override
    public String getName() {
        return "http.server.requests";
    }

    @Override
    public String getContextualName(ServiceObservationContext context) {
        final RequestLogAccess logAccess = context.requestContext().log();
        if (logAccess.isAvailable(RequestLogProperty.NAME)) {
            return logAccess.partial().fullName();
        } else {
            return context.getName();
        }
    }

    @Override
    public boolean supportsContext(Context context) {
        return context instanceof ServiceObservationContext;
    }
}
