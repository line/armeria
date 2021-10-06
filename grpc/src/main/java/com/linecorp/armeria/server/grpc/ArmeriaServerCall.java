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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.grpc.protocol.Base64DecoderUtil.byteBufConverter;
import static com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil.serializeTrailersAsMessage;
import static java.util.Objects.requireNonNull;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.ForwardingDecompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.HttpStreamDeframer;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.TransportStatusListener;
import com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.Codec;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link ServerCall.Listener}, and writing messages passed back to the response.
 */
final class ArmeriaServerCall<I, O> extends ServerCall<I, O>
        implements Subscriber<DeframedMessage>, TransportStatusListener {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerCall.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<ArmeriaServerCall> pendingMessagesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ArmeriaServerCall.class, "pendingMessages");

    private static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();

    private final MethodDescriptor<I, O> method;
    private final String simpleMethodName;

    private final StreamMessage<DeframedMessage> deframedRequest;
    private final ArmeriaMessageFramer responseFramer;

    private final HttpResponseWriter res;
    private final CompressorRegistry compressorRegistry;
    private final ServiceRequestContext ctx;
    private final SerializationFormat serializationFormat;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final boolean unsafeWrapRequestBuffers;
    private final ResponseHeaders defaultHeaders;

    @Nullable
    private final Executor blockingExecutor;
    @Nullable
    private final GrpcStatusFunction statusFunction;

    // Only set once.
    @Nullable
    private ServerCall.Listener<I> listener;
    @Nullable
    private ResponseHeaders responseHeaders;
    @Nullable
    private O firstResponse;
    @Nullable
    private final String clientAcceptEncoding;

    @Nullable
    private Compressor compressor;
    @Nullable
    private Subscription upstream;

    // Message compression defaults to being enabled unless a user disables it using a server interceptor.
    private boolean messageCompression = true;

    private boolean messageReceived;

    // state
    private volatile boolean cancelled;
    private volatile boolean clientStreamClosed;
    private volatile boolean listenerClosed;
    private boolean closeCalled;

    private int pendingRequests;
    private volatile int pendingMessages;

    ArmeriaServerCall(HttpRequest req,
                      MethodDescriptor<I, O> method,
                      String simpleMethodName,
                      CompressorRegistry compressorRegistry,
                      DecompressorRegistry decompressorRegistry,
                      HttpResponseWriter res,
                      int maxInboundMessageSizeBytes,
                      int maxOutboundMessageSizeBytes,
                      ServiceRequestContext ctx,
                      SerializationFormat serializationFormat,
                      @Nullable GrpcJsonMarshaller jsonMarshaller,
                      boolean unsafeWrapRequestBuffers,
                      boolean useBlockingTaskExecutor,
                      ResponseHeaders defaultHeaders,
                      @Nullable GrpcStatusFunction statusFunction) {
        requireNonNull(req, "req");
        this.method = requireNonNull(method, "method");
        this.simpleMethodName = requireNonNull(simpleMethodName, "simpleMethodName");
        this.ctx = requireNonNull(ctx, "ctx");
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        this.defaultHeaders = requireNonNull(defaultHeaders, "defaultHeaders");

        final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        requireNonNull(decompressorRegistry, "decompressorRegistry");

        final RequestHeaders clientHeaders = req.headers();
        final ByteBufAllocator alloc = ctx.alloc();
        final HttpStreamDeframer requestDeframer =
                new HttpStreamDeframer(decompressorRegistry, ctx, this, statusFunction,
                                       maxInboundMessageSizeBytes)
                        .decompressor(clientDecompressor(clientHeaders, decompressorRegistry));
        deframedRequest = req.decode(requestDeframer, alloc, byteBufConverter(alloc, grpcWebText));
        requestDeframer.setDeframedStreamMessage(deframedRequest);
        responseFramer = new ArmeriaMessageFramer(alloc, maxOutboundMessageSizeBytes, grpcWebText);

        this.res = requireNonNull(res, "res");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        clientAcceptEncoding =
                Strings.emptyToNull(clientHeaders.get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING));
        marshaller = new GrpcMessageMarshaller<>(alloc, serializationFormat, method, jsonMarshaller,
                                                 unsafeWrapRequestBuffers);
        this.unsafeWrapRequestBuffers = unsafeWrapRequestBuffers;
        blockingExecutor = useBlockingTaskExecutor ?
                           MoreExecutors.newSequentialExecutor(ctx.blockingTaskExecutor()) : null;
        this.statusFunction = statusFunction;

        res.whenComplete().handle((unused, t) -> {
            final EventLoop eventLoop = ctx.eventLoop();
            if (eventLoop.inEventLoop()) {
                maybeCancel();
            } else {
                eventLoop.execute(this::maybeCancel);
            }
            return null;
        });
    }

    /**
     * Cancels a call when the call was closed by a client, not by server.
     */
    private void maybeCancel() {
        if (!closeCalled) {
            cancelled = true;
            try (SafeCloseable ignore = ctx.push()) {
                close(Status.CANCELLED, new Metadata());
            }
        }
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            if (upstream == null) {
                pendingRequests += numMessages;
            } else {
                upstream.request(numMessages);
            }
        } else {
            ctx.eventLoop().execute(() -> request(numMessages));
        }
    }

    @Override
    public void sendHeaders(Metadata metadata) {
        if (ctx.eventLoop().inEventLoop()) {
            doSendHeaders(metadata);
        } else {
            ctx.eventLoop().execute(() -> doSendHeaders(metadata));
        }
    }

    private void doSendHeaders(Metadata metadata) {
        if (cancelled) {
            // call was already closed by a client or a timeout scheduler.
            return;
        }
        checkState(responseHeaders == null, "sendHeaders already called");
        checkState(!closeCalled, "call is closed");

        if (compressor == null || !messageCompression || clientAcceptEncoding == null) {
            compressor = Codec.Identity.NONE;
        } else {
            final List<String> acceptedEncodingsList =
                    ACCEPT_ENCODING_SPLITTER.splitToList(clientAcceptEncoding);
            if (!acceptedEncodingsList.contains(compressor.getMessageEncoding())) {
                // resort to using no compression.
                compressor = Codec.Identity.NONE;
            }
        }
        responseFramer.setCompressor(ForwardingCompressor.forGrpc(compressor));

        ResponseHeaders headers = defaultHeaders;

        if (compressor != Codec.Identity.NONE || InternalMetadata.headerCount(metadata) > 0) {
            headers = headers.withMutations(builder -> {
                if (compressor != Codec.Identity.NONE) {
                    builder.set(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());
                }
                MetadataUtil.fillHeaders(metadata, builder);
            });
        }

        // https://github.com/grpc/proposal/blob/4c4a06d95eb1e7d3d7d84c4c9505a99f2a721db9/A6-client-retries.md#L263
        // gRPC servers should delay the Response-Headers until the first response message or
        // until the application code chooses to send headers.
        responseHeaders = headers;
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
        if (cancelled) {
            // call was already closed by a client or a timeout scheduler
            return;
        }
        checkState(responseHeaders != null, "sendHeaders has not been called");
        checkState(!closeCalled, "call is closed");

        if (firstResponse == null) {
            // Write the response headers when the first response is received.
            if (!res.tryWrite(responseHeaders)) {
                maybeCancel();
                return;
            }
            firstResponse = message;
        }

        try {
            if (res.tryWrite(responseFramer.writePayload(marshaller.serializeResponse(message)))) {
                if (!method.getType().serverSendsOneMessage()) {
                    // Invoke onReady() only when server can send multiple messages.
                    res.whenConsumed().thenRun(() -> {
                        if (!closeCalled && pendingMessagesUpdater.decrementAndGet(this) == 0) {
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
            close(e, new Metadata());
        }
    }

    private void invokeOnReady() {
        try {
            if (listener != null) {
                listener.onReady();
            }
        } catch (Throwable t) {
            close(t, new Metadata());
        }
    }

    @Override
    public boolean isReady() {
        return !closeCalled && pendingMessages == 0;
    }

    @Override
    public void close(Status status, Metadata metadata) {
        close0(GrpcStatus.fromStatusFunction(statusFunction, ctx, status, metadata), metadata);
    }

    private void close(Throwable exception, Metadata metadata) {
        close0(GrpcStatus.fromThrowable(statusFunction, ctx, exception, metadata), metadata);
    }

    private void close0(Status status, Metadata metadata) {
        if (ctx.eventLoop().inEventLoop()) {
            doClose(status, metadata);
        } else {
            ctx.eventLoop().execute(() -> {
                doClose(status, metadata);
            });
        }
    }

    private void doClose(Status status, Metadata metadata) {
        if (cancelled) {
            // No need to write anything to client if cancelled already.
            closeListener(status, false);
            return;
        }

        if (status.getCode() == Code.CANCELLED && status.getCause() instanceof ClosedStreamException) {
            closeListener(status, false);
            return;
        }

        checkState(!closeCalled, "call already closed");
        closeCalled = true;

        boolean completed = true;
        if (status.getCode() == Code.CANCELLED && status.getCause() instanceof RequestTimeoutException) {
            // A call was finished by a timeout scheduler, not a user.
            completed = false;
        } else if (status.isOk() && method.getType().serverSendsOneMessage() && firstResponse == null) {
            // A call that should send a message incompletely finished.
            final String description = "Completed without a response";
            logger.warn("{} {} status: {}, metadata: {}", ctx, description, status, metadata);
            status = Status.CANCELLED.withDescription(description);
            completed = false;
        }

        final boolean trailersOnly;
        if (firstResponse != null) {
            // ResponseHeaders was written successfully.
            trailersOnly = false;
        } else {
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
                    closeListener(status, false);
                    return;
                }
            }
        }

        final HttpHeadersBuilder defaultTrailers =
                trailersOnly ? defaultHeaders.toBuilder() : HttpHeaders.builder();
        final HttpHeaders trailers = statusToTrailers(ctx, defaultTrailers, status, metadata);
        try {
            if (!trailersOnly && GrpcSerializationFormats.isGrpcWeb(serializationFormat)) {
                GrpcWebTrailers.set(ctx, trailers);
                // Normal trailers are not supported in grpc-web and must be encoded as a message.
                final ByteBuf serialized = serializeTrailersAsMessage(ctx.alloc(), trailers);
                if (res.tryWrite(responseFramer.writePayload(serialized, true))) {
                    res.close();
                }
            } else {
                if (res.tryWrite(trailers)) {
                    res.close();
                }
            }
        } finally {
            closeListener(status, completed);
        }
    }

    @VisibleForTesting
    boolean isCloseCalled() {
        return closeCalled;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public synchronized void setMessageCompression(boolean messageCompression) {
        responseFramer.setMessageCompression(messageCompression);
        this.messageCompression = messageCompression;
    }

    @Override
    public synchronized void setCompression(String compressorName) {
        checkState(responseHeaders == null, "sendHeaders has been called");
        compressor = compressorRegistry.lookupCompressor(compressorName);
        checkArgument(compressor != null, "Unable to find compressor by name %s", compressorName);
        responseFramer.setCompressor(ForwardingCompressor.forGrpc(compressor));
    }

    @Override
    public MethodDescriptor<I, O> getMethodDescriptor() {
        return method;
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
        try {
            final I request;
            final ByteBuf buf = message.buf();

            boolean success = false;
            try {
                // Special case for unary calls.
                if (messageReceived && method.getType() == MethodType.UNARY) {
                    closeListener(Status.INTERNAL.withDescription(
                            "More than one request messages for unary call or server streaming call"), false);
                    return;
                }
                messageReceived = true;

                if (closeCalled) {
                    return;
                }
                success = true;
            } finally {
                if (buf != null && !success) {
                    buf.release();
                }
            }

            final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);

            request = marshaller.deserializeRequest(message, grpcWebText);

            if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName, request),
                                                null);
            }

            if (unsafeWrapRequestBuffers && buf != null && !grpcWebText) {
                GrpcUnsafeBufferUtil.storeBuffer(buf, request, ctx);
            }

            if (blockingExecutor != null) {
                blockingExecutor.execute(() -> invokeOnMessage(request));
            } else {
                invokeOnMessage(request);
            }
        } catch (Throwable e) {
            upstream.cancel();
            close(e, new Metadata());
        }
    }

    private void invokeOnMessage(I request) {
        try (SafeCloseable ignored = ctx.push()) {
            assert listener != null;
            listener.onMessage(request);
        } catch (Throwable t) {
            upstream.cancel();
            close(t, new Metadata());
        }
    }

    @Override
    public void onComplete() {
        clientStreamClosed = true;
        if (!closeCalled) {
            if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName), null);
            }

            if (blockingExecutor != null) {
                blockingExecutor.execute(this::invokeHalfClose);
            } else {
                invokeHalfClose();
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        if (!closeCalled && !(t instanceof AbortedStreamException)) {
            close(t, new Metadata());
        }
    }

    private void invokeHalfClose() {
        try (SafeCloseable ignored = ctx.push()) {
            assert listener != null;
            listener.onHalfClose();
        } catch (Throwable t) {
            close(t, new Metadata());
        }
    }

    @Override
    public void transportReportStatus(Status status, Metadata unused) {
        // A server doesn't see trailers from the client so will never have Metadata here.

        if (closeCalled) {
            // We've already called close on the server-side and will close the listener with the server-side
            // status, so we ignore client transport status's at this point (it's usually the RST_STREAM
            // corresponding to a successful stream ending in practice, but even if it was an actual transport
            // failure there's no need to notify the server listener of it).
            return;
        }
        closeListener(status, false);
    }

    private void closeListener(Status newStatus, boolean completed) {
        if (!listenerClosed) {
            listenerClosed = true;

            ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(newStatus, firstResponse), null);

            if (!clientStreamClosed) {
                clientStreamClosed = true;
                deframedRequest.abort();
            }

            if (completed) {
                if (blockingExecutor != null) {
                    blockingExecutor.execute(this::invokeOnComplete);
                } else {
                    invokeOnComplete();
                }
            } else {
                cancelled = true;
                if (blockingExecutor != null) {
                    blockingExecutor.execute(this::invokeOnCancel);
                } else {
                    invokeOnCancel();
                }
                // Transport error, not business logic error, so reset the stream.
                if (!closeCalled) {
                    final StatusException statusException = newStatus.asException();
                    final Throwable cause = statusException.getCause();
                    if (cause != null) {
                        res.close(cause);
                    } else {
                        res.abort();
                    }
                }
            }
        }
    }

    private void invokeOnComplete() {
        try (SafeCloseable ignored = ctx.push()) {
            if (listener != null) {
                listener.onComplete();
            }
        } catch (Throwable t) {
            // This should not be possible with normal generated stubs which do not implement
            // onComplete, but is conceivable for a completely manually constructed stub.
            logger.warn("Error in gRPC onComplete handler.", t);
        }
    }

    private void invokeOnCancel() {
        try (SafeCloseable ignored = ctx.push()) {
            if (listener != null) {
                listener.onCancel();
            }
        } catch (Throwable t) {
            if (!closeCalled) {
                // A custom error when dealing with client cancel or transport issues should be
                // returned. We have already closed the listener, so it will not receive any more
                // callbacks as designed.
                close(t, new Metadata());
            }
        }
    }

    // Returns ResponseHeaders if headersSent == false or HttpHeaders otherwise.
    static HttpHeaders statusToTrailers(
            ServiceRequestContext ctx, HttpHeadersBuilder trailersBuilder, Status status, Metadata metadata) {
        GrpcTrailersUtil.addStatusMessageToTrailers(
                trailersBuilder, status.getCode().value(), status.getDescription());

        MetadataUtil.fillHeaders(metadata, trailersBuilder);

        if (ctx.config().verboseResponses() && status.getCause() != null) {
            final ThrowableProto proto = GrpcStatus.serializeThrowable(status.getCause());
            trailersBuilder.add(GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN,
                                Base64.getEncoder().encodeToString(proto.toByteArray()));
        }

        final HttpHeaders additionalTrailers = ctx.additionalResponseTrailers();
        ctx.mutateAdditionalResponseTrailers(HttpHeadersBuilder::clear);
        trailersBuilder.add(additionalTrailers);
        return trailersBuilder.build();
    }

    void setListener(Listener<I> listener) {
        checkState(this.listener == null, "listener already set");
        this.listener = requireNonNull(listener, "listener");
        invokeOnReady();
    }

    void startDeframing() {
        // Should start deframing after a listener is set.
        assert listener != null;
        deframedRequest.subscribe(this, ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Nullable
    private static Decompressor clientDecompressor(HttpHeaders headers, DecompressorRegistry registry) {
        final String encoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
        if (encoding == null) {
            return ForwardingDecompressor.forGrpc(Identity.NONE);
        }
        final io.grpc.Decompressor decompressor = registry.lookupDecompressor(encoding);
        if (decompressor != null) {
            return ForwardingDecompressor.forGrpc(decompressor);
        }
        return ForwardingDecompressor.forGrpc(Identity.NONE);
    }
}
