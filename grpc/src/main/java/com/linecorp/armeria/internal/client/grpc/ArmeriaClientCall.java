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

import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;
import static com.linecorp.armeria.internal.client.grpc.protocol.InternalGrpcWebUtil.messageBuf;
import static com.linecorp.armeria.internal.common.grpc.protocol.Base64DecoderUtil.byteBufConverter;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.EventLoopStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.client.grpc.protocol.InternalGrpcWebUtil;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.HttpStreamDeframer;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.internal.common.grpc.TransportStatusListener;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;
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
import io.netty.buffer.ByteBufAllocator;

/**
 * Encapsulates the state of a single client call, writing messages from the client and reading responses
 * from the server, passing to business logic via {@link ClientCall.Listener}.
 */
final class ArmeriaClientCall<I, O> extends ClientCall<I, O>
        implements Subscriber<DeframedMessage>, TransportStatusListener {

    private static final Runnable NO_OP = () -> {};

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaClientCall.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<ArmeriaClientCall> pendingMessagesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ArmeriaClientCall.class, "pendingMessages");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<ArmeriaClientCall, Runnable>
            pendingTaskUpdater = AtomicReferenceFieldUpdater.newUpdater(
            ArmeriaClientCall.class, Runnable.class, "pendingTask");

    private final DefaultClientRequestContext ctx;
    private final EndpointGroup endpointGroup;
    private final HttpClient httpClient;
    private final HttpRequestWriter req;
    private final MethodDescriptor<I, O> method;
    private final Map<MethodDescriptor<?, ?>, String> simpleMethodNames;
    private final CallOptions callOptions;
    private final ArmeriaMessageFramer requestFramer;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final CompressorRegistry compressorRegistry;
    private final SerializationFormat serializationFormat;
    private final boolean unsafeWrapResponseBuffers;
    @Nullable
    private final Executor executor;
    private final String advertisedEncodingsHeader;
    private final DecompressorRegistry decompressorRegistry;
    private final int maxInboundMessageSizeBytes;
    private final boolean grpcWebText;

    private final boolean endpointInitialized;
    @Nullable
    private volatile Runnable pendingTask;

    // Effectively final, only set once during start()
    @Nullable
    private Listener<O> listener;
    @Nullable
    private Subscription upstream;

    @Nullable
    private O firstResponse;
    private boolean closed;

    private int pendingRequests;
    private volatile int pendingMessages;

    ArmeriaClientCall(
            DefaultClientRequestContext ctx,
            EndpointGroup endpointGroup,
            HttpClient httpClient,
            HttpRequestWriter req,
            MethodDescriptor<I, O> method,
            Map<MethodDescriptor<?, ?>, String> simpleMethodNames,
            int maxOutboundMessageSizeBytes,
            int maxInboundMessageSizeBytes,
            CallOptions callOptions,
            CompressorRegistry compressorRegistry,
            DecompressorRegistry decompressorRegistry,
            SerializationFormat serializationFormat,
            @Nullable GrpcJsonMarshaller jsonMarshaller,
            boolean unsafeWrapResponseBuffers,
            String advertisedEncodingsHeader) {
        this.ctx = ctx;
        this.endpointGroup = endpointGroup;
        this.httpClient = httpClient;
        this.req = req;
        this.method = method;
        this.simpleMethodNames = simpleMethodNames;
        this.callOptions = callOptions;
        this.compressorRegistry = compressorRegistry;
        this.decompressorRegistry = decompressorRegistry;
        this.serializationFormat = serializationFormat;
        this.unsafeWrapResponseBuffers = unsafeWrapResponseBuffers;
        this.advertisedEncodingsHeader = advertisedEncodingsHeader;
        grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
        endpointInitialized = endpointGroup.whenReady().isDone();
        if (!endpointInitialized) {
            ctx.whenInitialized().handle((unused1, unused2) -> {
                runPendingTask();
                return null;
            });
        }

        requestFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes, grpcWebText);
        marshaller = new GrpcMessageMarshaller<>(ctx.alloc(), serializationFormat, method, jsonMarshaller,
                                                 unsafeWrapResponseBuffers);
        executor = callOptions.getExecutor();

        req.whenComplete().handle((unused1, unused2) -> {
            if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
                // Can reach here if the request stream was empty.
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName()), null);
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
        requestFramer.setCompressor(ForwardingCompressor.forGrpc(compressor));
        listener = responseListener;

        final long remainingNanos;
        if (callOptions.getDeadline() != null) {
            remainingNanos = callOptions.getDeadline().timeRemaining(TimeUnit.NANOSECONDS);
            if (remainingNanos <= 0) {
                final Status status = Status.DEADLINE_EXCEEDED
                        .augmentDescription("ClientCall started after deadline exceeded: " +
                                            callOptions.getDeadline());
                close(status, new Metadata());
            } else {
                ctx.setResponseTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofNanos(remainingNanos));
            }
        } else {
            remainingNanos = MILLISECONDS.toNanos(ctx.responseTimeoutMillis());
        }

        // Must come after handling deadline.
        prepareHeaders(compressor, metadata, remainingNanos);

        final HttpResponse res = initContextAndExecuteWithFallback(
                httpClient, ctx, endpointGroup, HttpResponse::from,
                (unused, cause) -> HttpResponse.ofFailure(GrpcStatus.fromThrowable(cause)
                                                                    .withDescription(cause.getMessage())
                                                                    .asRuntimeException()));

        final HttpStreamDeframer deframer =
                new HttpStreamDeframer(decompressorRegistry, ctx, this, null, maxInboundMessageSizeBytes);

        if (endpointInitialized) {
            subscribeToDeframer(res, deframer);
        } else {
            addPendingTask(() -> {
                subscribeToDeframer(res, deframer);
            });
        }
        responseListener.onReady();
    }

    private void subscribeToDeframer(HttpResponse res, HttpStreamDeframer deframer) {
        final ByteBufAllocator alloc = ctx.alloc();
        final StreamMessage<DeframedMessage> deframed =
                new DecodedHttpStreamMessage<>(new EventLoopStreamMessage<>(ctx.eventLoop()),
                                               res, deframer, alloc, byteBufConverter(alloc, grpcWebText));
        deframer.setDeframedStreamMessage(deframed);
        deframed.subscribe(this, ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public void request(int numMessages) {
        if (needsDirectInvocation()) {
            doRequest(numMessages);
        } else {
            execute(() -> doRequest(numMessages));
        }
    }

    private void doRequest(int numMessages) {
        if (method.getType().serverSendsOneMessage() && numMessages == 1) {
            // At least 2 requests are required for receiving trailers.
            numMessages = 2;
        }
        if (upstream == null) {
            pendingRequests += numMessages;
        } else {
            upstream.request(numMessages);
        }
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
        if (needsDirectInvocation()) {
            doCancel(message, cause);
        } else {
            execute(() -> doCancel(message, cause));
        }
    }

    private void doCancel(@Nullable String message, @Nullable Throwable cause) {
        if (message == null && cause == null) {
            cause = new CancellationException("Cancelled without a message or cause");
            logger.warn("Cancelling without a message or cause is suboptimal", cause);
        }
        if (closed) {
            return;
        }
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
        if (needsDirectInvocation()) {
            req.close();
        } else {
            execute(req::close);
        }
    }

    @Override
    public void sendMessage(I message) {
        pendingMessagesUpdater.incrementAndGet(this);
        if (needsDirectInvocation()) {
            doSendMessage(message);
        } else {
            execute(() -> doSendMessage(message));
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
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName(), message),
                                                null);
            }
            final ByteBuf serialized = marshaller.serializeRequest(message);
            req.write(requestFramer.writePayload(serialized));
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
        requestFramer.setMessageCompression(enabled);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        // This method is invoked in ctx.eventLoop.
        upstream = subscription;
        if (pendingRequests > 0) {
            subscription.request(pendingRequests);
            pendingRequests = 0;
        }
    }

    @Override
    public void onNext(DeframedMessage message) {
        if (GrpcSerializationFormats.isGrpcWeb(serializationFormat) && message.isTrailer()) {
            final ByteBuf buf;
            try {
                buf = messageBuf(message, ctx.alloc());
            } catch (Throwable t) {
                cancel(null, t);
                return;
            }
            try {
                final HttpHeaders trailers = InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                if (trailers == null) {
                    // Malformed trailers.
                    close(Status.INTERNAL.withDescription(serializationFormat.uriText() +
                                                          " trailers malformed: " +
                                                          buf.toString(StandardCharsets.UTF_8)),
                          new Metadata());
                } else {
                    GrpcWebTrailers.set(ctx, trailers);
                    GrpcStatus.reportStatus(trailers, this);
                }
            } finally {
                buf.release();
            }
            return;
        }

        try {
            final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
            final O msg = marshaller.deserializeResponse(message, grpcWebText);
            if (firstResponse == null) {
                firstResponse = msg;
            }

            final ByteBuf buf = message.buf();
            if (unsafeWrapResponseBuffers && buf != null && !grpcWebText) {
                GrpcUnsafeBufferUtil.storeBuffer(buf, msg, ctx);
            }

            try (SafeCloseable ignored = ctx.push()) {
                assert listener != null;
                listener.onMessage(msg);
            }
        } catch (Throwable t) {
            final Status status = GrpcStatus.fromThrowable(t);
            req.close(status.asException());
            close(status, new Metadata());
        }

        notifyExecutor();
    }

    @Override
    public void onError(Throwable t) {
        // Ignore - the client call is terminated by headers, not data.
    }

    @Override
    public void onComplete() {
        // Ignore - the client call is terminated by headers, not data.
    }

    @Override
    public void transportReportStatus(Status status, Metadata metadata) {
        close(status, metadata);
    }

    private void prepareHeaders(Compressor compressor, Metadata metadata, long remainingNanos) {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        if (compressor != Identity.NONE) {
            newHeaders.set(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());
        }

        if (!advertisedEncodingsHeader.isEmpty()) {
            newHeaders.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodingsHeader);
        }

        if (remainingNanos > 0) {
            newHeaders.add(GrpcHeaderNames.GRPC_TIMEOUT, TimeoutHeaderUtil.toHeaderValue(remainingNanos));
        }

        MetadataUtil.fillHeaders(metadata, newHeaders);

        final HttpRequest newReq = req.withHeaders(newHeaders);
        ctx.updateRequest(newReq);
    }

    private void close(Status status, Metadata metadata) {
        if (closed) {
            // 'close()' could be called twice if a call is closed with non-OK status.
            // See: https://github.com/line/armeria/issues/3799
            return;
        }
        closed = true;

        final Deadline deadline = callOptions.getDeadline();
        if (status.getCode() == Code.CANCELLED && deadline != null && deadline.isExpired()) {
            status = Status.DEADLINE_EXCEEDED;
            // Replace trailers to prevent mixing sources of status and trailers.
            metadata = new Metadata();
        }
        if (status.getCode() == Code.DEADLINE_EXCEEDED) {
            status = status.augmentDescription("deadline exceeded after " +
                                               MILLISECONDS.toNanos(ctx.responseTimeoutMillis()) + "ns.");
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.responseContent(GrpcLogUtil.rpcResponse(status, firstResponse), null);
        if (status.isOk()) {
            req.abort();
        } else {
            req.abort(status.asRuntimeException(metadata));
        }
        if (upstream != null) {
            upstream.cancel();
        }

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

    private boolean needsDirectInvocation() {
        return (endpointInitialized || ctx.whenInitialized().isDone()) && ctx.eventLoop().inEventLoop();
    }

    private void execute(Runnable task) {
        if (endpointInitialized || ctx.whenInitialized().isDone()) {
            ctx.eventLoop().execute(task);
        } else {
            addPendingTask(task);
        }
    }

    private void runPendingTask() {
        for (;;) {
            final Runnable pendingTask = this.pendingTask;
            if (pendingTaskUpdater.compareAndSet(this, pendingTask, NO_OP)) {
                if (pendingTask != null) {
                    if (ctx.eventLoop().inEventLoop()) {
                        pendingTask.run();
                    } else {
                        ctx.eventLoop().execute(pendingTask);
                    }
                }
                break;
            }
        }
    }

    private void addPendingTask(Runnable pendingTask) {
        if (!pendingTaskUpdater.compareAndSet(this, null, pendingTask)) {
            for (;;) {
                final Runnable oldPendingTask = this.pendingTask;
                assert oldPendingTask != null;
                if (oldPendingTask == NO_OP) {
                    if (ctx.eventLoop().inEventLoop()) {
                        pendingTask.run();
                    } else {
                        ctx.eventLoop().execute(pendingTask);
                    }
                    break;
                }
                final Runnable newPendingTask = () -> {
                    oldPendingTask.run();
                    pendingTask.run();
                };
                if (pendingTaskUpdater.compareAndSet(this, oldPendingTask, newPendingTask)) {
                    break;
                }
            }
        }
    }

    private String simpleMethodName() {
        String simpleMethodName = simpleMethodNames.get(method);
        if (simpleMethodName == null) {
            simpleMethodName = method.getBareMethodName();
        }
        return simpleMethodName;
    }
}
