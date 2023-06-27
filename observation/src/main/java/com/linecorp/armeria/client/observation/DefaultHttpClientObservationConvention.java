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

package com.linecorp.armeria.client.observation;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.observation.HttpClientObservationDocumentation.HighCardinalityKeys;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;

import io.micrometer.common.KeyValues;

class DefaultHttpClientObservationConvention implements HttpClientObservationConvention {

    static final DefaultHttpClientObservationConvention INSTANCE =
            new DefaultHttpClientObservationConvention();

    @Override
    public KeyValues getLowCardinalityKeyValues(HttpClientContext context) {
        // TODO: What to move here?
        return HttpClientObservationConvention.super.getLowCardinalityKeyValues(context);
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(HttpClientContext context) {
        final ClientRequestContext ctx = context.getClientRequestContext();
        KeyValues keyValues = KeyValues.of(
                HighCardinalityKeys.HTTP_METHOD.withValue(ctx.method().name()),
                HighCardinalityKeys.HTTP_PATH.withValue(ctx.path()),
                HighCardinalityKeys.HTTP_HOST.withValue(firstNonNull(ctx.authority(), "UNKNOWN")),
                HighCardinalityKeys.HTTP_URL.withValue(ctx.uri().toString())
        );
        if (context.getResponse() != null) {
            final RequestLog log = ctx.log().ensureComplete();
            keyValues = keyValues.and(HighCardinalityKeys.HTTP_PROTOCOL.withValue(protocol(log)));

            final String serFmt = serializationFormat(log);
            if (serFmt != null) {
                keyValues = keyValues.and(HighCardinalityKeys.HTTP_SERIALIZATION_FORMAT.withValue(serFmt));
            }

            final InetSocketAddress raddr = ctx.remoteAddress();
            if (raddr != null) {
                keyValues = keyValues.and(HighCardinalityKeys.ADDRESS_REMOTE.withValue(raddr.toString()));
            }

            final InetSocketAddress laddr = ctx.localAddress();
            if (laddr != null) {
                keyValues = keyValues.and(HighCardinalityKeys.ADDRESS_LOCAL.withValue(laddr.toString()));
            }
            keyValues = keyValues.and(HighCardinalityKeys.STATUS_CODE
                                              .withValue(log.responseStatus().codeAsText()));
            if (log.responseStatus().isError()) {
                keyValues = keyValues.and(HighCardinalityKeys.ERROR
                                                  .withValue(log.responseStatus().codeAsText()));
            } else if (log.responseCause() != null) {
                keyValues = keyValues.and(HighCardinalityKeys.ERROR
                                                  .withValue(log.responseCause().toString()));
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
        return "http.client.requests";
    }

    @Override
    public String getContextualName(HttpClientContext context) {
        final ClientRequestContext clientRequestContext = context.getClientRequestContext();
        final RequestLog log = clientRequestContext.log().ensureComplete();
        final String name = log.name();
        return firstNonNull(name, context.getName());
    }
}
