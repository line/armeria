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

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.observation.HttpServerObservationDocumentation.HighCardinalityKeys;
import com.linecorp.armeria.server.observation.HttpServerObservationDocumentation.LowCardinalityKeys;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationConvention;

final class DefaultServiceObservationConvention implements ObservationConvention<HttpServerContext> {

    static final DefaultServiceObservationConvention INSTANCE =
            new DefaultServiceObservationConvention();

    @Override
    public KeyValues getLowCardinalityKeyValues(HttpServerContext context) {
        final ServiceRequestContext ctx = context.serviceRequestContext();
        KeyValues keyValues = KeyValues.of(
                LowCardinalityKeys.HTTP_METHOD.withValue(ctx.method().name()));
        if (context.getResponse() != null) {
            final RequestLog log = ctx.log().ensureComplete();
            keyValues = keyValues.and(
                    LowCardinalityKeys.HTTP_PROTOCOL.withValue(protocol(log)));
            final String serFmt = serializationFormat(log);
            if (serFmt != null) {
                keyValues = keyValues.and(
                        LowCardinalityKeys.HTTP_SERIALIZATION_FORMAT.withValue(serFmt));
            }
            keyValues = keyValues.and(
                    LowCardinalityKeys.STATUS_CODE.withValue(log.responseStatus().codeAsText()));
        }
        return keyValues;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(HttpServerContext context) {
        final ServiceRequestContext ctx = context.serviceRequestContext();
        KeyValues keyValues = KeyValues.of(
                HighCardinalityKeys.HTTP_PATH.withValue(ctx.path()),
                HighCardinalityKeys.HTTP_HOST.withValue(
                        firstNonNull(context.httpRequest().authority(), "UNKNOWN")),
                HighCardinalityKeys.HTTP_URL.withValue(ctx.uri().toString())
        );
        if (context.getResponse() != null) {
            final RequestLog log = ctx.log().ensureComplete();

            final InetSocketAddress raddr = ctx.remoteAddress();
            keyValues = keyValues.and(
                    HighCardinalityKeys.ADDRESS_REMOTE.withValue(raddr.toString()));

            final InetSocketAddress laddr = ctx.localAddress();
            keyValues = keyValues.and(
                    HighCardinalityKeys.ADDRESS_LOCAL.withValue(laddr.toString()));

            final Throwable responseCause = log.responseCause();
            if (responseCause != null) {
                keyValues = keyValues.and(HighCardinalityKeys.ERROR.withValue(responseCause.toString()));
            } else if (log.responseStatus().isError()) {
                keyValues = keyValues.and(
                        HighCardinalityKeys.ERROR.withValue(log.responseStatus().codeAsText()));
            }
        }
        return keyValues;
    }

    /**
     * Returns the {@link SessionProtocol#uriText()} of the {@link RequestLog}.
     */
    private static String protocol(RequestLog requestLog) {
        return requestLog.scheme().sessionProtocol().uriText();
    }

    /**
     * Returns the {@link SerializationFormat#uriText()} if it's not {@link SerializationFormat#NONE}.
     */
    @Nullable
    private static String serializationFormat(RequestLog requestLog) {
        final SerializationFormat serFmt = requestLog.scheme().serializationFormat();
        return serFmt == SerializationFormat.NONE ? null : serFmt.uriText();
    }

    @Override
    public String getName() {
        return "http.server.requests";
    }

    @Override
    public String getContextualName(HttpServerContext context) {
        final ServiceRequestContext serviceRequestContext = context.serviceRequestContext();
        final RequestLog log = serviceRequestContext.log().ensureComplete();
        final String name = log.name();
        return firstNonNull(name, context.getName());
    }

    @Override
    public boolean supportsContext(Context context) {
        return context instanceof HttpServerContext;
    }
}
