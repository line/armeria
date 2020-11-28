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
import static com.linecorp.armeria.internal.client.grpc.InternalGrpcWebUtil.messageBuf;
import static com.linecorp.armeria.internal.common.grpc.protocol.HttpDeframerUtil.newHttpDeframer;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

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
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcWebTrailers;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.HttpStreamDeframerHandler;
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

    private final DefaultClientRequestContext ctx;
    private final EndpointGroup endpointGroup;
    private final HttpClient httpClient;
    private final HttpRequestWriter req;
    private final MethodDescriptor<I, O> method;
    private final CallOptions callOptions;
    private final ArmeriaMessageFramer messageFramer;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final CompressorRegistry compressorRegistry;
    @Nullable
    private HttpDeframer<DeframedMessage> responseReader;
    private final SerializationFormat serializationFormat;
    private final boolean unsafeWrapResponseBuffers;
    @Nullable
    private final Executor executor;
    @Nullable
    private final List<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings;
    private final String advertisedEncodingsHeader;
    private final DecompressorRegistry decompressorRegistry;
    private final int maxInboundMessageSizeBytes;
    private final boolean grpcWebText;

    // Effectively final, only set once during start()
    @Nullable
    private Listener<O> listener;
    @Nullable
    private Subscription upstream;

    @Nullable
    private O firstResponse;
    private boolean cancelCalled;

    private int pendingRequests;
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
            @Nullable GrpcJsonMarshaller jsonMarshaller,
            boolean unsafeWrapResponseBuffers,
            String advertisedEncodingsHeader,
            @Nullable List<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings) {
        this.ctx = ctx;
        this.endpointGroup = endpointGroup;
        this.httpClient = httpClient;
        this.req = req;
        this.method = method;
        this.callOptions = callOptions;
        this.compressorRegistry = compressorRegistry;
        this.decompressorRegistry = decompressorRegistry;
        this.serializationFormat = serializationFormat;
        this.unsafeWrapResponseBuffers = unsafeWrapResponseBuffers;
        this.advertisedEncodingsHeader = advertisedEncodingsHeader;
        grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;

        messageFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes, grpcWebText);
        marshaller = new GrpcMessageMarshaller<>(ctx.alloc(), serializationFormat, method, jsonMarshaller,
                                                 unsafeWrapResponseBuffers);
        executor = callOptions.getExecutor();
        this.exceptionMappings = exceptionMappings;

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
                (unused, cause) -> HttpResponse.ofFailure(GrpcStatus.fromThrowable(exceptionMappings, cause)
                                                                    .withDescription(cause.getMessage())
                                                                    .asRuntimeException()));

        final HttpStreamDeframerHandler handler =
                new HttpStreamDeframerHandler(decompressorRegistry, this, exceptionMappings,
                                              maxInboundMessageSizeBytes);
        responseReader = newHttpDeframer(handler, ctx.alloc(), grpcWebText);
        handler.setDeframer(responseReader);
        responseReader.subscribe(this, ctx.eventLoop());

        res.subscribe(responseReader, ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
        responseListener.onReady();
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            doRequest(numMessages);
        } else {
            ctx.eventLoop().execute(() -> doRequest(numMessages));
        }
    }

    private void doRequest(int numMessages) {
        if (upstream == null) {
            pendingRequests += numMessages;
        } else {
            upstream.request(numMessages);
        }
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
        if (ctx.eventLoop().inEventLoop()) {
            doCancel(message, cause);
        } else {
            ctx.eventLoop().execute(() -> doCancel(message, cause));
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
            ctx.eventLoop().execute(req::close);
        }
    }

    @Override
    public void sendMessage(I message) {
        pendingMessagesUpdater.incrementAndGet(this);
        if (ctx.eventLoop().inEventLoop()) {
            doSendMessage(message);
        } else {
            ctx.eventLoop().execute(() -> doSendMessage(message));
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
                        close(GrpcStatus.fromThrowable(exceptionMappings, t), new Metadata());
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
        if (GrpcSerializationFormats.isGrpcWeb(serializationFormat) && message.type() >> 7 == 1) {
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
                    GrpcStatus.reportStatus(trailers, responseReader, this);
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
            final Status status = GrpcStatus.fromThrowable(exceptionMappings, t);
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
        if (cancelCalled) {
            return;
        }
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
        if (responseReader != null) {
            responseReader.close();
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
}
