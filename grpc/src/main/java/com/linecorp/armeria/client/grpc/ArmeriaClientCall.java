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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.grpc.HttpStreamReader;
import com.linecorp.armeria.internal.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.internal.grpc.TransportStatusListener;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;

/**
 * Encapsulates the state of a single client call, writing messages from the client and reading responses
 * from the server, passing to business logic via {@link ClientCall.Listener}.
 */
class ArmeriaClientCall<I, O> extends ClientCall<I, O>
        implements ArmeriaMessageDeframer.Listener, TransportStatusListener {

    private static final Runnable NO_OP = () -> {
    };

    private static final Metadata EMPTY_METADATA = new Metadata();

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaClientCall.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<ArmeriaClientCall> pendingMessagesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ArmeriaClientCall.class, "pendingMessages");

    private final ClientRequestContext ctx;
    private final Client<HttpRequest, HttpResponse> httpClient;
    private final HttpRequestWriter req;
    private final MethodDescriptor<I, O> method;
    private final CallOptions callOptions;
    private final ArmeriaMessageFramer messageFramer;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final CompressorRegistry compressorRegistry;
    private final DecompressorRegistry decompressorRegistry;
    private final HttpStreamReader responseReader;
    private final boolean unsafeWrapResponseBuffers;
    @Nullable
    private final Executor executor;
    private final String advertisedEncodingsHeader;

    // Effectively final, only set once during start()
    @Nullable
    private Listener<O> listener;

    @Nullable
    private O firstResponse;
    private boolean cancelCalled;

    private volatile int pendingMessages;

    ArmeriaClientCall(
            ClientRequestContext ctx,
            Client<HttpRequest, HttpResponse> httpClient,
            HttpRequestWriter req,
            MethodDescriptor<I, O> method,
            int maxOutboundMessageSizeBytes,
            int maxInboundMessageSizeBytes,
            CallOptions callOptions,
            CompressorRegistry compressorRegistry,
            DecompressorRegistry decompressorRegistry,
            SerializationFormat serializationFormat,
            @Nullable MessageMarshaller jsonMarshaller,
            boolean unsafeWrapResponseBuffers,
            String advertisedEncodingsHeader) {
        this.ctx = ctx;
        this.httpClient = httpClient;
        this.req = req;
        this.method = method;
        this.callOptions = callOptions;
        this.compressorRegistry = compressorRegistry;
        this.decompressorRegistry = decompressorRegistry;
        this.unsafeWrapResponseBuffers = unsafeWrapResponseBuffers;
        this.advertisedEncodingsHeader = advertisedEncodingsHeader;
        messageFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes);
        marshaller = new GrpcMessageMarshaller<>(
                ctx.alloc(), serializationFormat, method, jsonMarshaller,
                unsafeWrapResponseBuffers);
        responseReader = new HttpStreamReader(
                decompressorRegistry,
                new ArmeriaMessageDeframer(this, maxInboundMessageSizeBytes, ctx.alloc()),
                this);
        executor = callOptions.getExecutor();
        req.completionFuture().whenComplete((unused1, unused2) -> {
            if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
                // Can reach here if the request stream was empty.
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method), null);
            }
        });
    }

    @Override
    public void start(Listener<O> responseListener, Metadata unused) {
        requireNonNull(responseListener, "responseListener");
        final Compressor compressor;
        if (callOptions.getCompressor() != null) {
            compressor = compressorRegistry.lookupCompressor(callOptions.getCompressor());
            if (compressor == null) {
                responseListener.onClose(
                        Status.INTERNAL.withDescription(
                                "Unable to find compressor by name " + callOptions.getCompressor()),
                        EMPTY_METADATA);
                return;
            }
        } else {
            compressor = Identity.NONE;
        }
        messageFramer.setCompressor(compressor);
        prepareHeaders(req.headers(), compressor);
        listener = responseListener;
        final HttpResponse res;
        try (SafeCloseable ignored = ctx.push()) {
            res = httpClient.execute(ctx, req);
        } catch (Exception e) {
            close(Status.fromThrowable(e));
            return;
        }
        res.subscribe(responseReader, ctx.eventLoop(), true);
        res.completionFuture().whenCompleteAsync(responseReader, ctx.eventLoop());
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            responseReader.request(numMessages);
        } else {
            ctx.eventLoop().submit(() -> responseReader.request(numMessages));
        }
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
        if (ctx.eventLoop().inEventLoop()) {
            doCancel(message, cause);
        } else {
            ctx.eventLoop().submit(() -> doCancel(message, cause));
        }
    }

    private void doCancel(@Nullable String message, @Nullable Throwable cause) {
        if (message == null && cause == null) {
            cause = new CancellationException("Cancelled without a message or cause");
            logger.warn("Cancelling without a message or cause is suboptimal", cause);
        }
        if (cancelCalled) {
            return;
        }
        cancelCalled = true;
        Status status = Status.CANCELLED;
        if (message != null) {
            status = status.withDescription(message);
        }
        if (cause != null) {
            status = status.withCause(cause);
        }
        close(status);
        req.abort();
    }

    @Override
    public void halfClose() {
        if (ctx.eventLoop().inEventLoop()) {
            req.close();
        } else {
            ctx.eventLoop().submit((Runnable) req::close);
        }
    }

    @Override
    public void sendMessage(I message) {
        pendingMessagesUpdater.incrementAndGet(this);
        if (ctx.eventLoop().inEventLoop()) {
            doSendMessage(message);
        } else {
            ctx.eventLoop().submit(() -> doSendMessage(message));
        }
    }

    @Override
    public boolean isReady() {
        return pendingMessages == 0;
    }

    private void doSendMessage(I message) {
        try {
            if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, message), null);
            }
            final ByteBuf serialized = marshaller.serializeRequest(message);
            req.write(messageFramer.writePayload(serialized));
            req.onDemand(() -> {
                if (pendingMessagesUpdater.decrementAndGet(this) == 0) {
                    try (SafeCloseable ignored = ctx.push()) {
                        listener.onReady();
                    } catch (Throwable t) {
                        close(Status.fromThrowable(t));
                    }
                }
            });
        } catch (Throwable t) {
            cancel(null, t);
        }
    }

    @Override
    public synchronized void setMessageCompression(boolean enabled) {
        messageFramer.setMessageCompression(enabled);
    }

    @Override
    public void messageRead(ByteBufOrStream message) {
        try {
            final O msg = marshaller.deserializeResponse(message);
            if (firstResponse == null) {
                firstResponse = msg;
            }

            if (unsafeWrapResponseBuffers && message.buf() != null) {
                GrpcUnsafeBufferUtil.storeBuffer(message.buf(), msg, ctx);
            }

            try (SafeCloseable ignored = ctx.push()) {
                listener.onMessage(msg);
            }
        } catch (Throwable t) {
            req.close(Status.fromThrowable(t).asException());
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    @Override
    public void endOfStream() {
        // Ignore - the client call is terminated by headers, not data.
    }

    @Override
    public void transportReportStatus(Status status) {
        close(status);
    }

    private void prepareHeaders(HttpHeaders headers, Compressor compressor) {
        if (compressor != Identity.NONE) {
            headers.set(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());
        }
        if (!advertisedEncodingsHeader.isEmpty()) {
            headers.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodingsHeader);
        }
        headers.add(GrpcHeaderNames.GRPC_TIMEOUT,
                    TimeoutHeaderUtil.toHeaderValue(
                            TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis())));
    }

    private void close(Status status) {
        ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(status, firstResponse), null);
        req.abort();
        responseReader.cancel();

        try (SafeCloseable ignored = ctx.push()) {
            listener.onClose(status, EMPTY_METADATA);
        }

        notifyExecutor();
    }

    /**
     * Armeria does not support {@link CallOptions} set by the user, however gRPC stubs set an {@link Executor}
     * within blocking stubs which is used to notify the stub when processing is finished. It's unclear why
     * the stubs use a loop and {@link Future#isDone()} instead of just blocking on
     * {@link Future#get}, but we make sure to run the {@link Executor} so the stub can
     * be notified of completion.
     */
    private void notifyExecutor() {
        if (executor != null) {
            executor.execute(NO_OP);
        }
    }
}
