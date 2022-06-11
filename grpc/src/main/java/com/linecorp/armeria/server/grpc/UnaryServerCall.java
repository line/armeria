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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.HttpMessageAggregator.aggregateData;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link Listener}, and writing messages passed back to the response.
 */
final class UnaryServerCall<I, O> extends AbstractServerCall<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(UnaryServerCall.class);

    private final HttpRequest req;
    private final CompletableFuture<HttpResponse> resFuture;
    private final ServiceRequestContext ctx;
    private final UnaryMessageDeframer requestDeframer;

    // Only set once.
    @Nullable
    private O responseMessage;
    private boolean deframingStarted;

    UnaryServerCall(HttpRequest req, MethodDescriptor<I, O> method, String simpleMethodName,
                    CompressorRegistry compressorRegistry, DecompressorRegistry decompressorRegistry,
                    HttpResponse res, CompletableFuture<HttpResponse> resFuture,
                    int maxRequestMessageLength, int maxResponseMessageLength,
                    ServiceRequestContext ctx, SerializationFormat serializationFormat,
                    @Nullable GrpcJsonMarshaller jsonMarshaller, boolean unsafeWrapRequestBuffers,
                    boolean useBlockingTaskExecutor, ResponseHeaders defaultHeaders,
                    @Nullable GrpcStatusFunction statusFunction, boolean autoCompress) {
        super(req, method, simpleMethodName, compressorRegistry, decompressorRegistry, res,
              maxResponseMessageLength, ctx, serializationFormat, jsonMarshaller, unsafeWrapRequestBuffers,
              useBlockingTaskExecutor, defaultHeaders, statusFunction, autoCompress);
        requireNonNull(req, "req");
        this.ctx = requireNonNull(ctx, "ctx");
        final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        requireNonNull(decompressorRegistry, "decompressorRegistry");

        final RequestHeaders clientHeaders = req.headers();
        requestDeframer = new UnaryMessageDeframer(ctx.alloc(), maxRequestMessageLength, grpcWebText)
                .decompressor(clientDecompressor(clientHeaders, decompressorRegistry));
        this.req = req;
        this.resFuture = requireNonNull(resFuture, "resFuture");
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            request0();
        } else {
            ctx.eventLoop().execute(this::request0);
        }
    }

    private void request0() {
        if (listener() == null) {
            return;
        }
        startDeframing();
    }

    @Override
    void startDeframing() {
        if (deframingStarted) {
            return;
        }
        deframingStarted = true;
        req.collect(ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS).handle((objects, cause) -> {
            if (cause != null) {
                onError(cause);
                return null;
            }

            try {
                onRequestMessage(deframe(objects), true);
            } catch (Exception ex) {
                onError(ex);
            }
            return null;
        });
    }

    private DeframedMessage deframe(List<HttpObject> objects) {
        if (objects.size() == 1) {
            final HttpObject object = objects.get(0);
            if (object instanceof HttpData) {
                return requestDeframer.deframe((HttpData) object);
            } else {
                logger.warn("{} An invalid HTTP object is received: {} (expected: {})",
                            ctx, object, HttpData.class.getName());
                return requestDeframer.deframe(HttpData.empty());
            }
        } else {
            return requestDeframer.deframe(objects);
        }
    }

    @Override
    public void sendMessage(O message) {
        if (ctx.eventLoop().inEventLoop()) {
            doSendMessage(message);
        } else {
            ctx.eventLoop().execute(() -> doSendMessage(message));
        }
    }

    private void doSendMessage(O message) {
        if (isCancelled()) {
            // call was already closed by a client or a timeout scheduler
            return;
        }
        checkState(responseHeaders() != null, "sendHeaders has not been called");
        checkState(responseMessage == null, "responseMessage is set already");
        checkState(!isCloseCalled(), "call is closed");
        responseMessage = message;
    }

    @Override
    public boolean isReady() {
        return !isCloseCalled();
    }

    @Override
    void doClose(Status status, Metadata metadata, boolean completed) {
        final ResponseHeaders responseHeaders = responseHeaders();
        final HttpResponse response;
        try {
            if (status.isOk()) {
                assert responseHeaders != null;
                assert responseMessage != null;
                final HttpData responseBody = toPayload(responseMessage);

                final HttpObject responseTrailers = responseTrailers(ctx, status, metadata, false);
                if (responseTrailers instanceof HttpData) {
                    // gRPC-Web encodes response trailers as response body.
                    final HttpData httpData =
                            aggregateData(responseBody, (HttpData) responseTrailers, ctx.alloc());
                    response = HttpResponse.of(responseHeaders, httpData);
                } else {
                    response = HttpResponse.of(responseHeaders, responseBody, (HttpHeaders) responseTrailers);
                }
            } else {
                final ResponseHeadersBuilder trailersBuilder;
                if (responseHeaders != null) {
                    trailersBuilder = responseHeaders.toBuilder();
                } else {
                    trailersBuilder = defaultResponseHeaders().toBuilder();
                }
                response = HttpResponse.of((ResponseHeaders) statusToTrailers(ctx, trailersBuilder,
                                                                              status, metadata));
            }

            // Set responseContent before closing stream to use responseCause in error handling
            ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(status, responseMessage), null);
            resFuture.complete(response);
            closeListener(status, completed, false);
        } catch (Exception ex) {
            resFuture.completeExceptionally(ex);
        }
    }

    @Nullable
    @Override
    O firstResponse() {
        return responseMessage;
    }
}
