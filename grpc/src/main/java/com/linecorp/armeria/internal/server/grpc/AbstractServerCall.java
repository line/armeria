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

package com.linecorp.armeria.internal.server.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.grpc.protocol.GrpcTrailersUtil.serializeTrailersAsMessage;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.grpc.ForwardingCompressor;
import com.linecorp.armeria.internal.common.grpc.ForwardingDecompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.StatusExceptionConverter;
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
import io.grpc.Status.Code;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link Listener}, and writing messages passed back to the response.
 */
public abstract class AbstractServerCall<I, O> extends ServerCall<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractServerCall.class);

    private static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();
    private static final String GRPC_STATUS_CODE_INTERNAL =
            String.valueOf(Status.Code.INTERNAL.value());

    private final MethodDescriptor<I, O> method;
    private final String simpleMethodName;

    private final HttpRequest req;
    private final ArmeriaMessageFramer responseFramer;

    private final HttpResponse res;
    private final CompressorRegistry compressorRegistry;
    private final ServiceRequestContext ctx;
    private final SerializationFormat serializationFormat;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final boolean unsafeWrapRequestBuffers;
    private final String clientAcceptEncoding;
    private final boolean autoCompression;

    @Nullable
    private final Executor blockingExecutor;
    private final GrpcExceptionHandlerFunction exceptionHandler;

    // Only set once.
    @Nullable
    private ServerCall.Listener<I> listener;

    // Message compression defaults to being enabled unless a user disables it using a server interceptor.
    private boolean messageCompression = true;

    private final ResponseHeaders defaultResponseHeaders;
    @Nullable
    private ResponseHeaders responseHeaders;
    @Nullable
    private Compressor compressor;

    private boolean messageReceived;

    // state
    private volatile boolean cancelled;
    private volatile boolean clientStreamClosed;
    private volatile boolean listenerClosed;
    private boolean closeCalled;

    protected AbstractServerCall(HttpRequest req,
                                 MethodDescriptor<I, O> method,
                                 String simpleMethodName,
                                 CompressorRegistry compressorRegistry,
                                 DecompressorRegistry decompressorRegistry,
                                 HttpResponse res,
                                 int maxResponseMessageLength,
                                 ServiceRequestContext ctx,
                                 SerializationFormat serializationFormat,
                                 @Nullable GrpcJsonMarshaller jsonMarshaller,
                                 boolean unsafeWrapRequestBuffers,
                                 ResponseHeaders defaultHeaders,
                                 GrpcExceptionHandlerFunction exceptionHandler,
                                 @Nullable Executor blockingExecutor,
                                 boolean autoCompression,
                                 boolean useMethodMarshaller) {
        requireNonNull(req, "req");
        this.method = requireNonNull(method, "method");
        this.simpleMethodName = requireNonNull(simpleMethodName, "simpleMethodName");
        this.ctx = requireNonNull(ctx, "ctx");
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");

        final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
        requireNonNull(decompressorRegistry, "decompressorRegistry");

        final ByteBufAllocator alloc = ctx.alloc();
        this.req = req;
        responseFramer = new ArmeriaMessageFramer(alloc, maxResponseMessageLength, grpcWebText);

        this.res = requireNonNull(res, "res");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        clientAcceptEncoding = req.headers().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "");
        this.autoCompression = autoCompression;
        marshaller = new GrpcMessageMarshaller<>(alloc, serializationFormat, method, jsonMarshaller,
                                                 unsafeWrapRequestBuffers, useMethodMarshaller);
        this.unsafeWrapRequestBuffers = unsafeWrapRequestBuffers;
        this.blockingExecutor = blockingExecutor;
        defaultResponseHeaders = defaultHeaders;
        this.exceptionHandler = exceptionHandler;

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

    protected final ResponseHeaders defaultResponseHeaders() {
        return defaultResponseHeaders;
    }

    @Nullable
    protected abstract O firstResponse();

    /**
     * Cancels a call when the call was closed by a client, not by server.
     */
    protected final void maybeCancel() {
        if (!closeCalled) {
            cancelled = true;
            try (SafeCloseable ignore = ctx.push()) {
                close(new ServerStatusAndMetadata(Status.CANCELLED, new Metadata(), true, true));
            }
        }
    }

    public final void close(Throwable exception) {
        close(exception, false);
    }

    public final void close(Throwable exception, boolean cancelled) {
        exception = Exceptions.peel(exception);
        final Metadata metadata = generateMetadataFromThrowable(exception);
        final Status status = exceptionHandler.apply(ctx, exception, metadata);
        close(new ServerStatusAndMetadata(status, metadata, false, cancelled), exception);
    }

    @Override
    public final void close(Status status, Metadata metadata) {
        if (status.getCause() == null) {
            close(new ServerStatusAndMetadata(status, metadata, false));
            return;
        }
        Status newStatus = exceptionHandler.apply(ctx, status.getCause(), metadata);
        assert newStatus != null;
        if (status.getDescription() != null) {
            newStatus = newStatus.withDescription(status.getDescription());
        }
        final ServerStatusAndMetadata statusAndMetadata =
                new ServerStatusAndMetadata(newStatus, metadata, false);
        close(statusAndMetadata);
    }

    public final void close(ServerStatusAndMetadata statusAndMetadata) {
        close(statusAndMetadata, null);
    }

    private void close(ServerStatusAndMetadata statusAndMetadata, @Nullable Throwable exception) {
        if (ctx.eventLoop().inEventLoop()) {
            doClose(statusAndMetadata, exception);
        } else {
            ctx.eventLoop().execute(() -> {
                doClose(statusAndMetadata, exception);
            });
        }
    }

    private void doClose(ServerStatusAndMetadata statusAndMetadata, @Nullable Throwable exception) {
        maybeLogFailedRequestContent(exception);
        Status status = statusAndMetadata.status();
        final Metadata metadata = statusAndMetadata.metadata();
        if (isCancelled()) {
            // No need to write anything to client if cancelled already.
            statusAndMetadata.shouldCancel();
            statusAndMetadata.setResponseContent(true);
            closeListener(statusAndMetadata);
            return;
        }

        if (status.getCode() == Code.CANCELLED && status.getCause() instanceof ClosedStreamException) {
            statusAndMetadata.shouldCancel();
            statusAndMetadata.setResponseContent(true);
            closeListener(statusAndMetadata);
            return;
        }

        checkState(!closeCalled, "call already closed. status: %s, exception: %s",
                   status, exception);
        closeCalled = true;

        if (status.isOk() && method.getType().serverSendsOneMessage() && firstResponse() == null) {
            // A call that should send a message incompletely finished.
            final String description = "Completed without a response";
            logger.warn("{} {} status: {}, metadata: {}", ctx, description, status, metadata);
            status = Status.CANCELLED.withDescription(description);
            statusAndMetadata = statusAndMetadata.withStatus(status);
            statusAndMetadata.shouldCancel();
        }
        doClose(statusAndMetadata);
    }

    protected abstract void doClose(ServerStatusAndMetadata statusAndMetadata);

    protected final void closeListener(ServerStatusAndMetadata statusAndMetadata) {
        final boolean setResponseContent = statusAndMetadata.setResponseContent();
        final boolean cancelled = statusAndMetadata.isShouldCancel();
        if (!listenerClosed) {
            listenerClosed = true;

            if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
                // Failed to deserialize a message into a request
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName), null);
            }

            if (setResponseContent) {
                ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(statusAndMetadata, firstResponse()),
                                                 null);
            }

            if (!clientStreamClosed) {
                clientStreamClosed = true;
                if (statusAndMetadata.status().isOk()) {
                    req.abort();
                } else {
                    req.abort(statusAndMetadata.asRuntimeException());
                }
            }

            if (!cancelled) {
                if (blockingExecutor != null) {
                    blockingExecutor.execute(this::invokeOnComplete);
                } else {
                    invokeOnComplete();
                }
            } else {
                this.cancelled = true;
                if (blockingExecutor != null) {
                    blockingExecutor.execute(this::invokeOnCancel);
                } else {
                    invokeOnCancel();
                }
                // Transport error, not business logic error, so reset the stream.
                if (!closeCalled) {
                    res.abort(statusAndMetadata.asRuntimeException());
                }
            }
        }
    }

    public void onRequestMessage(DeframedMessage message, boolean endOfStream) {
        try {
            final I request;
            final ByteBuf buf = message.buf();

            boolean success = false;
            try {
                // Special case for unary calls.
                if (messageReceived && method.getType() == MethodType.UNARY) {
                    final Status status = Status.INTERNAL.withDescription(
                            "More than one request messages for unary call or server streaming " +
                            "call");
                    closeListener(new ServerStatusAndMetadata(status, new Metadata(), true, true));
                    return;
                }
                messageReceived = true;

                if (closeCalled) {
                    return;
                }
                success = true;
            } finally {
                if (!success) {
                    message.close();
                }
            }

            final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(serializationFormat);
            request = marshaller.deserializeRequest(message, grpcWebText);
            maybeLogRequestContent(request);

            if (unsafeWrapRequestBuffers && buf != null && !grpcWebText) {
                GrpcUnsafeBufferUtil.storeBuffer(buf, request, ctx);
            }

            if (blockingExecutor != null) {
                blockingExecutor.execute(() -> invokeOnMessage(request, endOfStream));
            } else {
                invokeOnMessage(request, endOfStream);
            }
        } catch (Throwable cause) {
            close(cause, true);
        }
    }

    protected final void onRequestComplete() {
        clientStreamClosed = true;
        if (!closeCalled) {
            maybeLogRequestContent(null);
            if (blockingExecutor != null) {
                blockingExecutor.execute(this::invokeHalfClose);
            } else {
                invokeHalfClose();
            }
        }
    }

    protected final void invokeOnReady() {
        try {
            if (listener != null) {
                listener.onReady();
            }
        } catch (Throwable t) {
            close(t);
        }
    }

    private void invokeOnMessage(I request, boolean halfClose) {
        try (SafeCloseable ignored = ctx.push()) {
            assert listener != null;
            listener.onMessage(request);
            if (halfClose) {
                listener.onHalfClose();
            }
        } catch (Throwable cause) {
            close(cause);
        }
    }

    protected final void invokeHalfClose() {
        try (SafeCloseable ignored = ctx.push()) {
            assert listener != null;
            listener.onHalfClose();
        } catch (Throwable t) {
            close(t);
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
                close(t);
            }
        }
    }

    protected void onError(Throwable t) {
        if (!closeCalled && !(t instanceof AbortedStreamException)) {
            close(t, true);
        }
    }

    public final void setListener(Listener<I> listener) {
        checkState(this.listener == null, "listener already set");
        this.listener = requireNonNull(listener, "listener");
        invokeOnReady();
    }

    public abstract void startDeframing();

    @Nullable
    protected final ResponseHeaders responseHeaders() {
        return responseHeaders;
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
        if (isCancelled()) {
            // call was already closed by a client or a timeout scheduler.
            return;
        }
        checkState(responseHeaders == null, "sendHeaders already called");
        checkState(!closeCalled, "call is closed");

        final Compressor oldCompressor = compressor;
        if (messageCompression && !clientAcceptEncoding.isEmpty()) {
            final List<String> acceptedEncodings =
                    ACCEPT_ENCODING_SPLITTER.splitToList(clientAcceptEncoding);
            if (compressor != null) {
                if (!acceptedEncodings.contains(compressor.getMessageEncoding())) {
                    // resort to using no compression.
                    compressor = Codec.Identity.NONE;
                }
            } else if (autoCompression) {
                for (final String encoding : acceptedEncodings) {
                    final Compressor compressor0 = compressorRegistry.lookupCompressor(encoding);
                    if (compressor0 != null) {
                        compressor = compressor0;
                        break;
                    }
                }
            }
        } else {
            compressor = Codec.Identity.NONE;
        }
        if (compressor == null) {
            compressor = Codec.Identity.NONE;
        }
        if (oldCompressor != compressor) {
            // Update the old compressor to new one.
            responseFramer.setCompressor(ForwardingCompressor.forGrpc(compressor));
        }

        ResponseHeaders headers = defaultResponseHeaders;

        if (compressor != Codec.Identity.NONE || InternalMetadata.headerCount(metadata) > 0) {
            headers = headers.withMutations(builder -> {
                if (compressor != Codec.Identity.NONE) {
                    builder.set(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());
                }
                MetadataUtil.fillHeaders(metadata, builder);

                // Delete the custom content-length of the streaming response which might be set by wrongly
                // implemented stubs such as Monix-gRPC. https://github.com/monix/monix-grpc/issues/43
                // If a wrong content-length is set, an RST_STREAM error occurs at the Netty level.
                // We don't need to care about the content-length of a unary call because it is eventually
                // adjusted when the response is aggregated.
                if (!method.getType().serverSendsOneMessage() && builder.contentLength() > -1) {
                    builder.remove(HttpHeaderNames.CONTENT_LENGTH);
                }
            });
        }

        // https://github.com/grpc/proposal/blob/4c4a06d95eb1e7d3d7d84c4c9505a99f2a721db9/A6-client-retries.md#L263
        // gRPC servers should delay the Response-Headers until the first response message or
        // until the application code chooses to send headers.
        responseHeaders = headers;
    }

    protected final HttpData toPayload(O message) throws IOException {
        return responseFramer.writePayload(marshaller.serializeResponse(message));
    }

    protected final HttpObject responseTrailers(ServiceRequestContext ctx, Status status,
                                                Metadata metadata, boolean trailersOnly) {
        final HttpHeadersBuilder defaultTrailers =
                trailersOnly ? defaultResponseHeaders.toBuilder() : HttpHeaders.builder();
        final HttpHeaders trailers = statusToTrailers(ctx, defaultTrailers, status, metadata);
        if (!trailersOnly && GrpcSerializationFormats.isGrpcWeb(serializationFormat)) {
            GrpcWebTrailers.set(ctx, trailers);
            // Normal trailers are not supported in grpc-web and must be encoded as a message.
            final ByteBuf serialized = serializeTrailersAsMessage(ctx.alloc(), trailers);
            return responseFramer.writePayload(serialized, true);
        } else {
            return trailers;
        }
    }

    // Returns ResponseHeaders if headersSent == false or HttpHeaders otherwise.
    public static HttpHeaders statusToTrailers(
            ServiceRequestContext ctx, HttpHeadersBuilder trailersBuilder, Status status, Metadata metadata) {
        try {
            MetadataUtil.fillHeaders(metadata, trailersBuilder);
        } catch (Exception e) {
            // A buggy user-implemented custom metadata serializer may throw
            // an exception. Leave a log message and set the INTERNAL status.
            logger.warn("{} Failed to serialize metadata; overriding the original status ({}) with INTERNAL:",
                        ctx, status, e);
            return trailersBuilder
                    .set(GrpcHeaderNames.GRPC_STATUS, GRPC_STATUS_CODE_INTERNAL)
                    .build();
        }
        GrpcTrailersUtil.addStatusMessageToTrailers(
                trailersBuilder, status.getCode().value(), status.getDescription(), null);

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

    @Override
    public final synchronized void setMessageCompression(boolean messageCompression) {
        responseFramer.setMessageCompression(messageCompression);
        this.messageCompression = messageCompression;
    }

    @Override
    public final synchronized void setCompression(String compressorName) {
        checkState(responseHeaders == null, "sendHeaders has been called");
        compressor = compressorRegistry.lookupCompressor(compressorName);
        checkArgument(compressor != null, "Unable to find compressor by name %s", compressorName);
        responseFramer.setCompressor(ForwardingCompressor.forGrpc(compressor));
    }

    private static Metadata generateMetadataFromThrowable(Throwable exception) {
        @Nullable
        final Metadata metadata = Status.trailersFromThrowable(exception);
        return metadata != null ? metadata : new Metadata();
    }

    @Nullable
    protected static Decompressor clientDecompressor(HttpHeaders headers, DecompressorRegistry registry) {
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

    private void maybeLogRequestContent(@Nullable Object message) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            if (message == null) {
                ctx.logBuilder()
                   .requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName), null);
            } else {
                ctx.logBuilder()
                   .requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName, message), null);
            }
        }
    }

    private void maybeLogFailedRequestContent(@Nullable Throwable cause) {
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            logBuilder.requestContent(GrpcLogUtil.rpcRequest(method, simpleMethodName), null);
        }
        if (cause != null) {
            if (cause instanceof ArmeriaStatusException) {
                cause = StatusExceptionConverter.toGrpc((ArmeriaStatusException) cause);
            }
            logBuilder.endRequest(cause);
        }
    }

    public final boolean isCloseCalled() {
        return closeCalled;
    }

    @Override
    public final boolean isCancelled() {
        return cancelled;
    }

    @Nullable
    public final Executor blockingExecutor() {
        return blockingExecutor;
    }

    public final EventLoop eventLoop() {
        return ctx.eventLoop();
    }

    @Override
    public final MethodDescriptor<I, O> getMethodDescriptor() {
        return method;
    }

    public final ServiceRequestContext ctx() {
        return ctx;
    }

    public final GrpcExceptionHandlerFunction exceptionHandler() {
        return exceptionHandler;
    }
}
