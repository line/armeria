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

import javax.annotation.Nullable;

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
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.GrpcWebTrailers;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.ForwardingDecompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.HttpStreamDeframerHandler;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.TransportStatusListener;
import com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil;
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
import io.grpc.StatusException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

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
    private boolean sendHeadersCalled;
    private boolean closeCalled;

    private int pendingRequests;
    private volatile int pendingMessages;

    ArmeriaServerCall(HttpRequest req,
                      MethodDescriptor<I, O> method,
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
        this.ctx = requireNonNull(ctx, "ctx");
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        this.defaultHeaders = requireNonNull(defaultHeaders, "defaultHeaders");

        final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        requireNonNull(decompressorRegistry, "decompressorRegistry");

        final RequestHeaders clientHeaders = req.headers();
        final ByteBufAllocator alloc = ctx.alloc();
        final HttpStreamDeframerHandler handler =
                new HttpStreamDeframerHandler(decompressorRegistry, this, statusFunction,
                                              maxInboundMessageSizeBytes)
                        .decompressor(clientDecompressor(clientHeaders, decompressorRegistry));
        deframedRequest = req.deframe(handler, alloc, byteBufConverter(alloc, grpcWebText));
        handler.setDeframedStreamMessage(deframedRequest);
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

        res.whenComplete().handleAsync((unused, t) -> {
            if (!closeCalled) {
                // Closed by client, not by server.
                cancelled = true;
                try (SafeCloseable ignore = ctx.push()) {
                    close(Status.CANCELLED, new Metadata());
                }
            }
            return null;
        }, ctx.eventLoop());
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
            // call was already closed by client or with non-OK status.
            return;
        }
        checkState(!sendHeadersCalled, "sendHeaders already called");
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

        sendHeadersCalled = true;
        res.write(headers);
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
            // call was already closed by client or with non-OK status
            return;
        }
        checkState(sendHeadersCalled, "sendHeaders has not been called");
        checkState(!closeCalled, "call is closed");

        if (firstResponse == null) {
            firstResponse = message;
        }

        try {
            res.write(responseFramer.writePayload(marshaller.serializeResponse(message)));
            res.whenConsumed().thenRun(() -> {
                if (pendingMessagesUpdater.decrementAndGet(this) == 0) {
                    if (blockingExecutor != null) {
                        blockingExecutor.execute(this::invokeOnReady);
                    } else {
                        invokeOnReady();
                    }
                }
            });
        } catch (RuntimeException e) {
            close(e, new Metadata());
            throw e;
        } catch (Throwable t) {
            close(t, new Metadata());
            throw new RuntimeException(t);
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
        if (ctx.eventLoop().inEventLoop()) {
            doClose(GrpcStatus.fromStatusFunction(statusFunction, status), metadata);
        } else {
            ctx.eventLoop().execute(() -> {
                doClose(GrpcStatus.fromStatusFunction(statusFunction, status), metadata);
            });
        }
    }

    private void close(Throwable exception, Metadata metadata) {
        if (ctx.eventLoop().inEventLoop()) {
            doClose(GrpcStatus.fromThrowable(statusFunction, exception), metadata);
        } else {
            ctx.eventLoop().execute(() -> {
                doClose(GrpcStatus.fromThrowable(statusFunction, exception), metadata);
            });
        }
    }

    private void doClose(Status status, Metadata metadata) {
        if (cancelled) {
            // No need to write anything to client if cancelled already.
            closeListener(status);
            return;
        }

        checkState(!closeCalled, "call already closed");
        closeCalled = true;

        final HttpHeaders trailers = statusToTrailers(
                ctx, sendHeadersCalled ? HttpHeaders.builder() : defaultHeaders.toBuilder(),
                status, metadata);
        try {
            if (sendHeadersCalled && GrpcSerializationFormats.isGrpcWeb(serializationFormat)) {
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
            closeListener(status);
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
        checkState(!sendHeadersCalled, "sendHeaders has been called");
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
                            "More than one request messages for unary call or server streaming call"));
                    return;
                }
                messageReceived = true;

                if (isCancelled()) {
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
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, request), null);
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
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method), null);
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

            // Based on the implementation of ServerCalls of gRPC-Java, onReady() is called only by
            // onHalfClose() of UnaryServerCallListener, which is used for UNARY and SERVER_STREAMING.
            // https://github.com/grpc/grpc-java/blob/9b73e2365da502a466b01544f102cd487e374428/stub/src/main/java/io/grpc/stub/ServerCalls.java#L188
            final MethodType methodType = method.getType();
            if (methodType == MethodType.UNARY || methodType == MethodType.SERVER_STREAMING) {
                listener.onReady();
            }
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
        closeListener(status);
    }

    private void closeListener(Status newStatus) {
        if (!listenerClosed) {
            listenerClosed = true;

            ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(newStatus, firstResponse), null);

            final boolean ok = newStatus.isOk();
            if (!clientStreamClosed) {
                clientStreamClosed = true;
                deframedRequest.abort();
            }

            if (ok) {
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
