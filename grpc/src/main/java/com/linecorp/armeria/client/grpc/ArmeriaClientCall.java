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

import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.HttpStreamReader;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.internal.common.grpc.TransportStatusListener;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.buffer.ByteBuf;
import io.netty.util.ByteProcessor;
import io.netty.util.ByteProcessor.IndexOfProcessor;

/**
 * Encapsulates the state of a single client call, writing messages from the client and reading responses
 * from the server, passing to business logic via {@link ClientCall.Listener}.
 */
final class ArmeriaClientCall<I, O> extends ClientCall<I, O>
        implements ArmeriaMessageDeframer.Listener, TransportStatusListener {

    private static final Runnable NO_OP = () -> {
    };

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaClientCall.class);

    private static final ByteProcessor FIND_COLON = new IndexOfProcessor((byte) ':');

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<ArmeriaClientCall> pendingMessagesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ArmeriaClientCall.class, "pendingMessages");

    private final DefaultClientRequestContext ctx;
    private final EndpointGroup endpointGroup;
    private final HttpClient httpClient;
    private final HttpRequestWriter req;
    private final MethodDescriptor<I, O> method;
    private final CallOptions callOptions;
    private final ArmeriaMessageFramer messageFramer;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final CompressorRegistry compressorRegistry;
    private final HttpStreamReader responseReader;
    private final boolean unsafeWrapResponseBuffers;
    @Nullable
    private final Executor executor;
    private final String advertisedEncodingsHeader;
    private final SerializationFormat serializationFormat;

    // Effectively final, only set once during start()
    @Nullable
    private Listener<O> listener;

    @Nullable
    private O firstResponse;
    private boolean cancelCalled;

    private volatile int pendingMessages;

    ArmeriaClientCall(
            DefaultClientRequestContext ctx,
            EndpointGroup endpointGroup,
            HttpClient httpClient,
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
        this.endpointGroup = endpointGroup;
        this.httpClient = httpClient;
        this.req = req;
        this.method = method;
        this.callOptions = callOptions;
        this.compressorRegistry = compressorRegistry;
        this.unsafeWrapResponseBuffers = unsafeWrapResponseBuffers;
        this.advertisedEncodingsHeader = advertisedEncodingsHeader;
        this.serializationFormat = serializationFormat;
        messageFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes);
        marshaller = new GrpcMessageMarshaller<>(
                ctx.alloc(), serializationFormat, method, jsonMarshaller,
                unsafeWrapResponseBuffers);
        responseReader = new HttpStreamReader(
                decompressorRegistry,
                new ArmeriaMessageDeframer(this, maxInboundMessageSizeBytes, ctx.alloc()),
                this);
        executor = callOptions.getExecutor();

        req.whenComplete().handle((unused1, unused2) -> {
            if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
                // Can reach here if the request stream was empty.
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method), null);
            }
            return null;
        });
    }

    @Override
    public void start(Listener<O> responseListener, Metadata metadata) {
        requireNonNull(responseListener, "responseListener");
        requireNonNull(metadata, "metadata");
        final Compressor compressor;
        if (callOptions.getCompressor() != null) {
            compressor = compressorRegistry.lookupCompressor(callOptions.getCompressor());
            if (compressor == null) {
                responseListener.onClose(
                        Status.INTERNAL.withDescription(
                                "Unable to find compressor by name " + callOptions.getCompressor()),
                        new Metadata());
                return;
            }
        } else {
            compressor = Identity.NONE;
        }
        messageFramer.setCompressor(ForwardingCompressor.forGrpc(compressor));
        prepareHeaders(compressor, metadata);
        listener = responseListener;

        if (callOptions.getDeadline() != null) {
            final long remainingMillis = callOptions.getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
            if (remainingMillis <= 0) {
                final Status status = Status.DEADLINE_EXCEEDED
                        .augmentDescription(
                                "ClientCall started after deadline exceeded: " +
                                callOptions.getDeadline());
                close(status, new Metadata());
            } else {
                ctx.setResponseTimeoutAfterMillis(remainingMillis);
                ctx.setResponseTimeoutHandler(() -> {
                    final Status status = Status.DEADLINE_EXCEEDED
                            .augmentDescription(
                                    "deadline exceeded after " +
                                    TimeUnit.MILLISECONDS.toNanos(remainingMillis) + "ns.");
                    close(status, new Metadata());
                });
            }
        }

        final HttpResponse res = initContextAndExecuteWithFallback(
                httpClient, ctx, endpointGroup,
                (unused, cause) -> HttpResponse.ofFailure(GrpcStatus.fromThrowable(cause)
                                                                    .withDescription(cause.getMessage())
                                                                    .asRuntimeException()));

        res.subscribe(responseReader, ctx.eventLoop(), WITH_POOLED_OBJECTS);
        res.whenComplete().handleAsync(responseReader, ctx.eventLoop());
        responseListener.onReady();
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
        close(status, new Metadata());
        if (cause == null) {
            req.abort();
        } else {
            req.abort(cause);
        }
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
        final RequestLogAccess log = ctx.log();
        if (log.isComplete()) {
            // Completed already; no need to send anymore.
            return;
        }

        try {
            if (!log.isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, message), null);
            }
            final ByteBuf serialized = marshaller.serializeRequest(message);
            req.write(messageFramer.writePayload(serialized));
            req.whenConsumed().thenRun(() -> {
                if (pendingMessagesUpdater.decrementAndGet(this) == 0) {
                    try (SafeCloseable ignored = ctx.push()) {
                        assert listener != null;
                        listener.onReady();
                    } catch (Throwable t) {
                        close(GrpcStatus.fromThrowable(t), new Metadata());
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
    public void messageRead(DeframedMessage message) {
        if (GrpcSerializationFormats.isGrpcWeb(serializationFormat) && message.type() >> 7 == 1) {
            // grpc-web trailers
            final ByteBuf buf = message.buf();
            // trailers never compressed
            assert buf != null;
            try {
                final HttpHeaders trailers = parseGrpcWebTrailers(buf);
                if (trailers == null) {
                    // Malformed trailers.
                    close(Status.INTERNAL
                                  .withDescription("grpc-web trailers malformed: " +
                                                   buf.toString(StandardCharsets.UTF_8)),
                          new Metadata());
                } else {
                    GrpcStatus.reportStatus(trailers, responseReader, this);
                }
            } finally {
                buf.release();
            }
            return;
        }
        try {
            final O msg = marshaller.deserializeResponse(message);
            if (firstResponse == null) {
                firstResponse = msg;
            }

            final ByteBuf buf = message.buf();
            if (unsafeWrapResponseBuffers && buf != null) {
                GrpcUnsafeBufferUtil.storeBuffer(buf, msg, ctx);
            }

            try (SafeCloseable ignored = ctx.push()) {
                assert listener != null;
                listener.onMessage(msg);
            }
        } catch (Throwable t) {
            req.close(GrpcStatus.fromThrowable(t).asException());
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }

        notifyExecutor();
    }

    @Override
    public void endOfStream() {
        // Ignore - the client call is terminated by headers, not data.
    }

    @Override
    public void transportReportStatus(Status status, Metadata metadata) {
        close(status, metadata);
    }

    private void prepareHeaders(Compressor compressor, Metadata metadata) {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        if (compressor != Identity.NONE) {
            newHeaders.set(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());
        }

        if (!advertisedEncodingsHeader.isEmpty()) {
            newHeaders.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodingsHeader);
        }

        newHeaders.add(GrpcHeaderNames.GRPC_TIMEOUT,
                       TimeoutHeaderUtil.toHeaderValue(
                               TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis())));

        MetadataUtil.fillHeaders(metadata, newHeaders);

        final HttpRequest newReq = req.withHeaders(newHeaders);
        ctx.updateRequest(newReq);
    }

    private void close(Status status, Metadata metadata) {
        final Deadline deadline = callOptions.getDeadline();
        if (status.getCode() == Code.CANCELLED && deadline != null && deadline.isExpired()) {
            status = Status.DEADLINE_EXCEEDED.augmentDescription(
                    "ClientCall was cancelled at or after deadline.");
            // Replace trailers to prevent mixing sources of status and trailers.
            metadata = new Metadata();
        }
        ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(status, firstResponse), null);
        if (status.isOk()) {
            req.abort();
        } else {
            req.abort(status.asRuntimeException(metadata));
        }
        responseReader.cancel();

        try (SafeCloseable ignored = ctx.push()) {
            assert listener != null;
            listener.onClose(status, metadata);
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

    @Nullable
    private static HttpHeaders parseGrpcWebTrailers(ByteBuf buf) {
        final HttpHeadersBuilder trailers = HttpHeaders.builder();
        while (buf.readableBytes() > 0) {
            int start = buf.forEachByte(ByteProcessor.FIND_NON_LINEAR_WHITESPACE);
            if (start == -1) {
                return null;
            }
            int endExclusive;
            if (buf.getByte(start) == ':') {
                // We need to skip the pseudoheader colon when searching for the separator.
                buf.skipBytes(1);
                endExclusive = buf.forEachByte(FIND_COLON);
                buf.readerIndex(start);
            } else {
                endExclusive = buf.forEachByte(FIND_COLON);
            }
            if (endExclusive == -1) {
                return null;
            }
            final CharSequence name = buf.readCharSequence(endExclusive - start, StandardCharsets.UTF_8);
            buf.readerIndex(endExclusive + 1);
            start = buf.forEachByte(ByteProcessor.FIND_NON_LINEAR_WHITESPACE);
            buf.readerIndex(start);
            endExclusive = buf.forEachByte(ByteProcessor.FIND_CRLF);
            final CharSequence value = buf.readCharSequence(endExclusive - start, StandardCharsets.UTF_8);
            trailers.add(name, value.toString());
            start = buf.forEachByte(ByteProcessor.FIND_NON_CRLF);
            if (start != -1) {
                buf.readerIndex(start);
            } else {
                // Nothing but CRLF remaining, we're done.
                buf.skipBytes(buf.readableBytes());
            }
        }
        return trailers.build();
    }
}
