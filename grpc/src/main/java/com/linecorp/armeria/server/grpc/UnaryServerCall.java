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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.linecorp.armeria.common.AggregationOptions;
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
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;
import com.linecorp.armeria.internal.server.grpc.ServerStatusAndMetadata;
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

    private final HttpRequest req;
    private final CompletableFuture<HttpResponse> resFuture;
    private final ServiceRequestContext ctx;
    private final UnaryMessageDeframer requestDeframer;

    // Only set once.
    @Nullable
    private O responseMessage;

    UnaryServerCall(HttpRequest req, MethodDescriptor<I, O> method, String simpleMethodName,
                    CompressorRegistry compressorRegistry, DecompressorRegistry decompressorRegistry,
                    HttpResponse res, CompletableFuture<HttpResponse> resFuture,
                    int maxRequestMessageLength, int maxResponseMessageLength,
                    ServiceRequestContext ctx, SerializationFormat serializationFormat,
                    @Nullable GrpcJsonMarshaller jsonMarshaller, boolean unsafeWrapRequestBuffers,
                    ResponseHeaders defaultHeaders,
                    @Nullable GrpcExceptionHandlerFunction exceptionHandler,
                    @Nullable Executor blockingExecutor,
                    boolean autoCompress,
                    boolean useMethodMarshaller) {
        super(req, method, simpleMethodName, compressorRegistry, decompressorRegistry, res,
              maxResponseMessageLength, ctx, serializationFormat, jsonMarshaller, unsafeWrapRequestBuffers,
              defaultHeaders, exceptionHandler, blockingExecutor, autoCompress, useMethodMarshaller);
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
        // The request of unary call is not reactive. `startDeframing()` will push the request message to
        // the stub through `listener.onMessage()` after `listener.onReady()` is invoked.
    }

    @Override
    public void startDeframing() {
        req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
           .handle((aggregatedHttpRequest, cause) -> {
               if (cause != null) {
                   onError(cause);
                   return null;
               }

               try {
                   onRequestMessage(requestDeframer.deframe(aggregatedHttpRequest.content()), true);
               } catch (Exception ex) {
                   // An exception could be raised when the deframer detects malformed data which is released by
                   // the try-with-resource block. So `objects` don't need to be released here.
                   onError(ex);
               }
               return null;
           });
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
    public void doClose(ServerStatusAndMetadata statusAndMetadata) {
        final ResponseHeaders responseHeaders = responseHeaders();
        final Status status = statusAndMetadata.status();
        final Metadata metadata = statusAndMetadata.metadata();
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
            ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(statusAndMetadata, responseMessage), null);
            statusAndMetadata.setResponseContent(false);
            resFuture.complete(response);
        } catch (Exception ex) {
            statusAndMetadata.shouldCancel();
            resFuture.completeExceptionally(ex);
        } finally {
            closeListener(statusAndMetadata);
        }
    }

    @Nullable
    @Override
    protected O firstResponse() {
        return responseMessage;
    }
}
