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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcLogUtil;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;

/**
 * A {@link Channel} backed by an armeria {@link Client}. Stores the {@link ClientBuilderParams} and other
 * {@link Client} params for the associated gRPC stub.
 */
class ArmeriaChannel extends Channel implements ClientBuilderParams {

    /**
     * See {@link ManagedChannelBuilder} for default setting.
     */
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 4 * 1024 * 1024;

    private final ClientBuilderParams params;
    private final Client<HttpRequest, HttpResponse> httpClient;

    private final MeterRegistry meterRegistry;
    private final SessionProtocol sessionProtocol;
    private final Endpoint endpoint;
    private final SerializationFormat serializationFormat;
    private final MessageMarshaller jsonMarshaller;

    ArmeriaChannel(ClientBuilderParams params,
                   Client<HttpRequest, HttpResponse> httpClient,
                   MeterRegistry meterRegistry,
                   SessionProtocol sessionProtocol,
                   Endpoint endpoint,
                   SerializationFormat serializationFormat,
                   @Nullable MessageMarshaller jsonMarshaller) {
        this.params = params;
        this.httpClient = httpClient;
        this.meterRegistry = meterRegistry;
        this.sessionProtocol = sessionProtocol;
        this.endpoint = endpoint;
        this.serializationFormat = serializationFormat;
        this.jsonMarshaller = jsonMarshaller;
    }

    @Override
    public <I, O> ClientCall<I, O> newCall(
            MethodDescriptor<I, O> method, CallOptions callOptions) {
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders
                        .of(HttpMethod.POST, uri().getPath() + method.getFullMethodName())
                        .set(HttpHeaderNames.CONTENT_TYPE, serializationFormat.mediaType().toString()));
        ClientRequestContext ctx = newContext(HttpMethod.POST, req);
        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method), null);
        ctx.logBuilder().deferResponseContent();
        return new ArmeriaClientCall<>(
                ctx,
                httpClient,
                req,
                method,
                options().getOrElse(GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES,
                                    ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE),
                options().getOrElse(
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES,
                        options().getOrElse(
                                ClientOption.DEFAULT_MAX_RESPONSE_LENGTH,
                                (long) DEFAULT_MAX_INBOUND_MESSAGE_SIZE).intValue()),
                callOptions,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                serializationFormat,
                jsonMarshaller);
    }

    @Override
    public String authority() {
        return params.uri().getAuthority();
    }

    @Override
    public ClientFactory factory() {
        return params.factory();
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

    private ClientRequestContext newContext(HttpMethod method, HttpRequest req) {
        final ReleasableHolder<EventLoop> eventLoop = factory().acquireEventLoop(endpoint);
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                eventLoop.get(),
                meterRegistry,
                sessionProtocol,
                endpoint,
                method,
                uri().getRawPath(),
                uri().getRawQuery(),
                null,
                options(),
                req);
        ctx.log().addListener(log -> eventLoop.release(), RequestLogAvailability.COMPLETE);
        return ctx;
    }
}
