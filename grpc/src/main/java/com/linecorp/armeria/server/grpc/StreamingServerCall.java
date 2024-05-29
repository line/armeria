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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.HttpStreamDeframer;
import com.linecorp.armeria.internal.common.grpc.TransportStatusListener;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;
import com.linecorp.armeria.internal.server.grpc.ServerStatusAndMetadata;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.netty.buffer.ByteBufAllocator;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link ServerCall.Listener}, and writing messages passed back to the response.
 */
final class StreamingServerCall<I, O> extends AbstractServerCall<I, O>
        implements Subscriber<DeframedMessage>, TransportStatusListener {

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<StreamingServerCall> pendingMessagesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(StreamingServerCall.class, "pendingMessages");

    private final MethodDescriptor<I, O> method;
    private final StreamMessage<DeframedMessage> deframedRequest;
    private final HttpResponseWriter res;
    private final ServiceRequestContext ctx;

    @Nullable
    private O firstResponse;
    @Nullable
    private Subscription upstream;

    // state
    private int pendingRequests;
    private volatile int pendingMessages;

    StreamingServerCall(HttpRequest req, MethodDescriptor<I, O> method, String simpleMethodName,
                        CompressorRegistry compressorRegistry, DecompressorRegistry decompressorRegistry,
                        HttpResponseWriter res, int maxRequestMessageLength, int maxResponseMessageLength,
                        ServiceRequestContext ctx, SerializationFormat serializationFormat,
                        @Nullable GrpcJsonMarshaller jsonMarshaller, boolean unsafeWrapRequestBuffers,
                        ResponseHeaders defaultHeaders,
                        @Nullable GrpcExceptionHandlerFunction exceptionHandler,
                        @Nullable Executor blockingExecutor, boolean autoCompress,
                        boolean useMethodMarshaller) {
        super(req, method, simpleMethodName, compressorRegistry, decompressorRegistry, res,
              maxResponseMessageLength, ctx, serializationFormat, jsonMarshaller, unsafeWrapRequestBuffers,
              defaultHeaders, exceptionHandler, blockingExecutor, autoCompress, useMethodMarshaller);
        requireNonNull(req, "req");
        this.method = requireNonNull(method, "method");
        this.ctx = requireNonNull(ctx, "ctx");
        final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        requireNonNull(decompressorRegistry, "decompressorRegistry");

        final RequestHeaders clientHeaders = req.headers();
        final ByteBufAllocator alloc = ctx.alloc();
        final HttpStreamDeframer requestDeframer =
                new HttpStreamDeframer(decompressorRegistry, ctx, this,
                                       exceptionHandler, maxRequestMessageLength, grpcWebText, true)
                        .decompressor(clientDecompressor(clientHeaders, decompressorRegistry));
        deframedRequest = req.decode(requestDeframer, alloc);
        requestDeframer.setDeframedStreamMessage(deframedRequest);
        this.res = requireNonNull(res, "res");
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            request0(numMessages);
        } else {
            ctx.eventLoop().execute(() -> request0(numMessages));
        }
    }

    private void request0(int numMessages) {
        if (upstream == null) {
            pendingRequests += numMessages;
        } else {
            upstream.request(numMessages);
        }
    }

    @Override
    public void startDeframing() {
        deframedRequest.subscribe(this, ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public void sendMessage(O message) {
        pendingMessagesUpdater.incrementAndGet(this);
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
        final ResponseHeaders responseHeaders = responseHeaders();
        checkState(responseHeaders != null, "sendHeaders has not been called");
        checkState(!isCloseCalled(), "call is closed");

        if (firstResponse == null) {
            // Write the response headers when the first response is received.
            if (!res.tryWrite(responseHeaders)) {
                maybeCancel();
                return;
            }
            firstResponse = message;
        }

        try {
            if (res.tryWrite(toPayload(message))) {
                if (!method.getType().serverSendsOneMessage()) {
                    // Invoke onReady() only when server can send multiple messages.
                    res.whenConsumed().thenRun(() -> {
                        if (!isCloseCalled() && pendingMessagesUpdater.decrementAndGet(this) == 0) {
                            final Executor blockingExecutor = blockingExecutor();
                            if (blockingExecutor != null) {
                                blockingExecutor.execute(this::invokeOnReady);
                            } else {
                                invokeOnReady();
                            }
                        }
                    });
                }
            } else {
                maybeCancel();
            }
        } catch (Throwable e) {
            close(e, true);
        }
    }

    @Override
    public boolean isReady() {
        return !isCloseCalled() && pendingMessages == 0;
    }

    @Override
    public void doClose(ServerStatusAndMetadata statusAndMetadata) {
        final Status status = statusAndMetadata.status();
        final Metadata metadata = statusAndMetadata.metadata();
        final boolean trailersOnly;
        if (firstResponse != null) {
            // ResponseHeaders was written successfully.
            trailersOnly = false;
        } else {
            final ResponseHeaders responseHeaders = responseHeaders();
            if (!status.isOk() || responseHeaders == null) {
                // Trailers-Only is permitted for calls that produce an immediate error.
                // https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#responses
                trailersOnly = true;
            } else {
                // A unary response should not reach hear.
                // The status should be non-OK if serverSendsOneMessage's firstResponse is null.
                assert !method.getType().serverSendsOneMessage();

                // SERVER_STREAMING or BIDI_STREAMING may not produce a response.
                // Try to write the pending response headers.
                if (res.tryWrite(responseHeaders)) {
                    trailersOnly = false;
                } else {
                    // A stream was closed already.
                    statusAndMetadata.shouldCancel();
                    statusAndMetadata.setResponseContent(true);
                    closeListener(statusAndMetadata);
                    return;
                }
            }
        }

        // Set responseContent before closing stream to use responseCause in error handling
        ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(statusAndMetadata, firstResponse), null);
        try {
            if (res.tryWrite(responseTrailers(ctx, status, metadata, trailersOnly))) {
                res.close();
            }
        } finally {
            statusAndMetadata.setResponseContent(false);
            closeListener(statusAndMetadata);
        }
    }

    @Override
    protected @Nullable O firstResponse() {
        return firstResponse;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        // 'subscribe()' only happens in the constructor of ArmeriaServerCall.
        upstream = subscription;
        if (pendingRequests > 0) {
            upstream.request(pendingRequests);
            pendingRequests = 0;
        }
    }

    @Override
    public void onNext(DeframedMessage message) {
        onRequestMessage(message, false);
    }

    @Override
    public void onComplete() {
        onRequestComplete();
    }

    @Override
    public void onError(Throwable t) {
        if (!isCloseCalled() && !(t instanceof AbortedStreamException)) {
            close(t, true);
        }
    }

    @Override
    public void transportReportStatus(Status status, Metadata metadata) {
        // A server doesn't see trailers from the client so will never have Metadata here.

        if (isCloseCalled()) {
            // We've already called close on the server-side and will close the listener with the server-side
            // status, so we ignore client transport status's at this point (it's usually the RST_STREAM
            // corresponding to a successful stream ending in practice, but even if it was an actual transport
            // failure there's no need to notify the server listener of it).
            return;
        }
        closeListener(new ServerStatusAndMetadata(status, metadata, true, true));
    }
}
