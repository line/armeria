/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import java.net.URI;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.Unwrappable;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.codec.http.HttpHeaderValues;

/**
 * A {@link Channel} backed by an armeria {@link HttpClient}. Stores the {@link ClientBuilderParams} and other
 * {@link HttpClient} params for the associated gRPC stub.
 */
final class ArmeriaChannel extends Channel implements ClientBuilderParams, Unwrappable {

    /**
     * See {@link ManagedChannelBuilder} for default setting.
     */
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 4 * 1024 * 1024;

    private final ClientBuilderParams params;
    private final HttpClient httpClient;

    private final MeterRegistry meterRegistry;
    private final SessionProtocol sessionProtocol;
    private final SerializationFormat serializationFormat;
    @Nullable
    private final MessageMarshaller jsonMarshaller;
    private final String advertisedEncodingsHeader;

    ArmeriaChannel(ClientBuilderParams params,
                   HttpClient httpClient,
                   MeterRegistry meterRegistry,
                   SessionProtocol sessionProtocol,
                   SerializationFormat serializationFormat,
                   @Nullable MessageMarshaller jsonMarshaller) {
        this.params = params;
        this.httpClient = httpClient;
        this.meterRegistry = meterRegistry;
        this.sessionProtocol = sessionProtocol;
        this.serializationFormat = serializationFormat;
        this.jsonMarshaller = jsonMarshaller;

        advertisedEncodingsHeader = String.join(
                ",", DecompressorRegistry.getDefaultInstance().getAdvertisedMessageEncodings());
    }

    @Override
    public <I, O> ClientCall<I, O> newCall(
            MethodDescriptor<I, O> method, CallOptions callOptions) {
        final HttpRequestWriter req = HttpRequest.streaming(
                RequestHeaders.of(HttpMethod.POST, uri().getPath() + method.getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, serializationFormat.mediaType(),
                                  HttpHeaderNames.TE, HttpHeaderValues.TRAILERS));
        final DefaultClientRequestContext ctx = newContext(HttpMethod.POST, req);
        ctx.logBuilder().name(method.getFullMethodName());
        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().deferRequestContent();
        ctx.logBuilder().deferResponseContent();
        return new ArmeriaClientCall<>(
                ctx,
                params.endpointGroup(),
                httpClient,
                req,
                method,
                options().getOrElse(GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES,
                                    ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE),
                options().getOrElse(
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES,
                        options().getOrElse(
                                ClientOption.MAX_RESPONSE_LENGTH,
                                (long) DEFAULT_MAX_INBOUND_MESSAGE_SIZE).intValue()),
                callOptions,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                serializationFormat,
                jsonMarshaller,
                options().getOrElse(GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS, false),
                advertisedEncodingsHeader);
    }

    @Override
    public String authority() {
        return params.uri().getAuthority();
    }

    @Override
    public Scheme scheme() {
        return params.scheme();
    }

    @Override
    public EndpointGroup endpointGroup() {
        return params.endpointGroup();
    }

    @Override
    public String absolutePathRef() {
        return params.absolutePathRef();
    }

    @Override
    public URI uri() {
        return params.uri();
    }

    @Override
    public Class<?> clientType() {
        return params.clientType();
    }

    @Override
    public ClientOptions options() {
        return params.options();
    }

    @Override
    public <T> T as(Class<T> type) {
        final T unwrapped = Unwrappable.super.as(type);
        if (unwrapped != null) {
            return unwrapped;
        }

        return httpClient.as(type);
    }

    private DefaultClientRequestContext newContext(HttpMethod method, HttpRequest req) {
        return new DefaultClientRequestContext(
                meterRegistry,
                sessionProtocol,
                options().requestIdGenerator().get(),
                method,
                req.path(),
                null,
                null,
                options(),
                req,
                null,
                System.nanoTime(),
                SystemInfo.currentTimeMicros());
    }
}
