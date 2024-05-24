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
package com.linecorp.armeria.internal.client.grpc;

import static com.linecorp.armeria.internal.client.grpc.GrpcClientUtil.maxInboundMessageSizeBytes;
import static com.linecorp.armeria.internal.common.grpc.GrpcExchangeTypeUtil.toExchangeType;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.grpc.GrpcClientOptions;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcCallOptions;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.internal.common.RequestTargetCache;

import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.codec.http.HttpHeaderValues;

/**
 * A {@link Channel} backed by an armeria {@link HttpClient}. Stores the {@link ClientBuilderParams} and other
 * {@link HttpClient} params for the associated gRPC stub.
 */
final class ArmeriaChannel extends Channel implements ClientBuilderParams, Unwrappable {

    private static final Map<MethodType, RequestOptions> REQUEST_OPTIONS_MAP;

    static {
        final EnumMap<MethodType, RequestOptions> requestOptionsMap = new EnumMap<>(MethodType.class);
        for (MethodType methodType : MethodType.values()) {
            if (methodType == MethodType.UNKNOWN) {
                continue;
            }
            requestOptionsMap.put(methodType, newRequestOptions(toExchangeType(methodType)));
        }
        REQUEST_OPTIONS_MAP = Maps.immutableEnumMap(requestOptionsMap);
    }

    private final ClientBuilderParams params;
    private final HttpClient httpClient;

    private final MeterRegistry meterRegistry;
    private final SessionProtocol sessionProtocol;
    private final SerializationFormat serializationFormat;
    @Nullable
    private final GrpcJsonMarshaller jsonMarshaller;
    private final Map<MethodDescriptor<?, ?>, String> simpleMethodNames;
    private final int maxOutboundMessageSizeBytes;
    private final int maxInboundMessageSizeBytes;
    private final boolean unsafeWrapResponseBuffers;
    private final Compressor compressor;
    private final DecompressorRegistry decompressorRegistry;
    private final CallCredentials credentials0;
    private final GrpcExceptionHandlerFunction exceptionHandler;
    private final boolean useMethodMarshaller;

    ArmeriaChannel(ClientBuilderParams params,
                   HttpClient httpClient,
                   MeterRegistry meterRegistry,
                   SessionProtocol sessionProtocol,
                   SerializationFormat serializationFormat,
                   @Nullable GrpcJsonMarshaller jsonMarshaller,
                   Map<MethodDescriptor<?, ?>, String> simpleMethodNames) {
        this.params = params;
        this.httpClient = httpClient;
        this.meterRegistry = meterRegistry;
        this.sessionProtocol = sessionProtocol;
        this.serializationFormat = serializationFormat;
        this.jsonMarshaller = jsonMarshaller;
        this.simpleMethodNames = simpleMethodNames;

        final ClientOptions options = options();
        maxOutboundMessageSizeBytes = options.get(GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES);
        maxInboundMessageSizeBytes = maxInboundMessageSizeBytes(options);
        unsafeWrapResponseBuffers = options.get(GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS);
        useMethodMarshaller = options.get(GrpcClientOptions.USE_METHOD_MARSHALLER);
        compressor = options.get(GrpcClientOptions.COMPRESSOR);
        decompressorRegistry = options.get(GrpcClientOptions.DECOMPRESSOR_REGISTRY);
        credentials0 = options.get(GrpcClientOptions.CALL_CREDENTIALS);
        exceptionHandler = options.get(GrpcClientOptions.EXCEPTION_HANDLER);
    }

    @Override
    public <I, O> ClientCall<I, O> newCall(MethodDescriptor<I, O> method, CallOptions callOptions) {
        final RequestHeadersBuilder headersBuilder =
                RequestHeaders.builder(HttpMethod.POST, uri().getPath() + method.getFullMethodName())
                              .contentType(serializationFormat.mediaType())
                              .set(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS.toString());
        final String callAuthority = callOptions.getAuthority();
        if (!Strings.isNullOrEmpty(callAuthority)) {
            headersBuilder.authority(callAuthority);
        }

        final HttpRequestWriter req = HttpRequest.streaming(headersBuilder.build());
        final DefaultClientRequestContext ctx = newContext(HttpMethod.POST, req, method);

        GrpcCallOptions.set(ctx, callOptions);

        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT,
                               RequestLogProperty.RESPONSE_CONTENT);

        final HttpClient client;

        CallCredentials credentials = callOptions.getCredentials();
        if (credentials == NullCallCredentials.INSTANCE) {
            credentials = null;
        }
        if (credentials == null) {
            if (credentials0 != NullCallCredentials.INSTANCE) {
                credentials = credentials0;
            }
        }
        if (credentials != null) {
            client = new CallCredentialsDecoratingClient(
                    httpClient, credentials, method,
                    !Strings.isNullOrEmpty(callAuthority) ? callAuthority : authority());
        } else {
            client = httpClient;
        }

        return new ArmeriaClientCall<>(
                ctx,
                params.endpointGroup(),
                client,
                req,
                method,
                simpleMethodNames,
                maxOutboundMessageSizeBytes,
                maxInboundMessageSizeBytes,
                callOptions,
                compressor,
                CompressorRegistry.getDefaultInstance(),
                decompressorRegistry,
                serializationFormat,
                jsonMarshaller,
                unsafeWrapResponseBuffers,
                exceptionHandler,
                useMethodMarshaller);
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

    private <I, O> DefaultClientRequestContext newContext(HttpMethod method, HttpRequest req,
                                                          MethodDescriptor<I, O> methodDescriptor) {
        final String path = req.path();
        final RequestTarget reqTarget = RequestTarget.forClient(path);
        assert reqTarget != null : path;
        RequestTargetCache.putForClient(path, reqTarget);

        return new DefaultClientRequestContext(
                meterRegistry,
                sessionProtocol,
                options().requestIdGenerator().get(),
                method,
                reqTarget,
                options(),
                req,
                null,
                REQUEST_OPTIONS_MAP.get(methodDescriptor.getType()),
                System.nanoTime(),
                SystemInfo.currentTimeMicros());
    }

    private static RequestOptions newRequestOptions(ExchangeType exchangeType) {
        return RequestOptions.builder()
                             .exchangeType(exchangeType)
                             .build();
    }
}
