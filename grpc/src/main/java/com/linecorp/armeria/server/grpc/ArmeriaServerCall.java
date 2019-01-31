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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.util.AsciiString.c2b;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.grpc.GrpcStatus;
import com.linecorp.armeria.internal.grpc.HttpStreamReader;
import com.linecorp.armeria.internal.grpc.StatusMessageEscaper;
import com.linecorp.armeria.internal.grpc.TransportStatusListener;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.unsafe.ByteBufHttpData;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.Codec;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.StatusException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.AsciiString;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link ServerCall.Listener}, and writing messages passed back to the response.
 */
class ArmeriaServerCall<I, O> extends ServerCall<I, O>
        implements ArmeriaMessageDeframer.Listener, TransportStatusListener {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerCall.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<ArmeriaServerCall> pendingMessagesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ArmeriaServerCall.class, "pendingMessages");

    // Only most significant bit of a byte is set.
    @VisibleForTesting
    static final byte TRAILERS_FRAME_HEADER = (byte) (1 << 7);

    private static final Metadata EMPTY_METADATA = new Metadata();

    private static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();

    private final MethodDescriptor<I, O> method;

    private final HttpStreamReader messageReader;
    private final ArmeriaMessageFramer messageFramer;

    private final HttpResponseWriter res;
    private final CompressorRegistry compressorRegistry;
    private final DecompressorRegistry decompressorRegistry;
    private final ServiceRequestContext ctx;
    private final SerializationFormat serializationFormat;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final boolean useBlockingTaskExecutor;
    private final boolean unsafeWrapRequestBuffers;
    private final String advertisedEncodingsHeader;

    // Only set once.
    @Nullable
    private ServerCall.Listener<I> listener;
    @Nullable
    private O firstResponse;
    @Nullable
    private final String clientAcceptEncoding;

    @Nullable
    private Compressor compressor;
    private boolean messageCompression;
    private boolean messageReceived;

    // state
    private volatile boolean cancelled;
    private volatile boolean clientStreamClosed;
    private volatile boolean listenerClosed;
    private boolean sendHeadersCalled;
    private boolean closeCalled;

    private volatile int pendingMessages;

    ArmeriaServerCall(HttpHeaders clientHeaders,
                      MethodDescriptor<I, O> method,
                      CompressorRegistry compressorRegistry,
                      DecompressorRegistry decompressorRegistry,
                      HttpResponseWriter res,
                      int maxInboundMessageSizeBytes,
                      int maxOutboundMessageSizeBytes,
                      ServiceRequestContext ctx,
                      SerializationFormat serializationFormat,
                      @Nullable MessageMarshaller jsonMarshaller,
                      boolean unsafeWrapRequestBuffers,
                      boolean useBlockingTaskExecutor,
                      String advertisedEncodingsHeader) {
        requireNonNull(clientHeaders, "clientHeaders");
        this.method = requireNonNull(method, "method");
        this.ctx = requireNonNull(ctx, "ctx");
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        messageReader = new HttpStreamReader(
                requireNonNull(decompressorRegistry, "decompressorRegistry"),
                new ArmeriaMessageDeframer(
                        this,
                        maxInboundMessageSizeBytes,
                        ctx.alloc())
                        .decompressor(clientDecompressor(clientHeaders, decompressorRegistry)),
                this);
        messageFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes);
        this.res = requireNonNull(res, "res");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        clientAcceptEncoding =
                Strings.emptyToNull(clientHeaders.get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING));
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        marshaller = new GrpcMessageMarshaller<>(ctx.alloc(), serializationFormat, method, jsonMarshaller,
                                                 unsafeWrapRequestBuffers);
        this.unsafeWrapRequestBuffers = unsafeWrapRequestBuffers;
        this.advertisedEncodingsHeader = advertisedEncodingsHeader;

        res.completionFuture().handleAsync((unused, t) -> {
            if (!closeCalled) {
                // Closed by client, not by server.
                cancelled = true;
                try (SafeCloseable ignore = ctx.pushIfAbsent()) {
                    close(Status.CANCELLED, EMPTY_METADATA);
                }
            }
            return null;
        }, ctx.eventLoop());
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            messageReader.request(numMessages);
        } else {
            ctx.eventLoop().submit(() -> messageReader.request(numMessages));
        }
    }

    @Override
    public void sendHeaders(Metadata unusedGrpcMetadata) {
        if (ctx.eventLoop().inEventLoop()) {
            doSendHeaders(unusedGrpcMetadata);
        } else {
            ctx.eventLoop().submit(() -> doSendHeaders(unusedGrpcMetadata));
        }
    }

    private void doSendHeaders(Metadata unusedGrpcMetadata) {
        checkState(!sendHeadersCalled, "sendHeaders already called");
        checkState(!closeCalled, "call is closed");

        final HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);

        headers.contentType(serializationFormat.mediaType());

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
        messageFramer.setCompressor(compressor);

        // Always put compressor, even if it's identity.
        headers.add(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());

        if (!advertisedEncodingsHeader.isEmpty()) {
            headers.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodingsHeader);
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
            ctx.eventLoop().submit(() -> doSendMessage(message));
        }
    }

    private void doSendMessage(O message) {
        checkState(sendHeadersCalled, "sendHeaders has not been called");
        checkState(!closeCalled, "call is closed");

        if (firstResponse == null) {
            firstResponse = message;
        }

        try {
            res.write(messageFramer.writePayload(marshaller.serializeResponse(message)));
            res.onDemand(() -> {
                if (pendingMessagesUpdater.decrementAndGet(this) == 0) {
                    if (useBlockingTaskExecutor) {
                        ctx.blockingTaskExecutor().execute(this::invokeOnReady);
                    } else {
                        invokeOnReady();
                    }
                }
            });
        } catch (RuntimeException e) {
            close(Status.fromThrowable(e), EMPTY_METADATA);
            throw e;
        } catch (Throwable t) {
            close(Status.fromThrowable(t), EMPTY_METADATA);
            throw new RuntimeException(t);
        }
    }

    private void invokeOnReady() {
        try {
            listener.onReady();
        } catch (Throwable t) {
            close(Status.fromThrowable(t), EMPTY_METADATA);
        }
    }

    @Override
    public boolean isReady() {
        return !closeCalled && pendingMessages == 0;
    }

    @Override
    public void close(Status status, Metadata unusedGrpcMetadata) {
        if (ctx.eventLoop().inEventLoop()) {
            doClose(status, unusedGrpcMetadata);
        } else {
            ctx.eventLoop().submit(() -> doClose(status, unusedGrpcMetadata));
        }
    }

    private void doClose(Status status, Metadata unusedGrpcMetadata) {
        checkState(!closeCalled, "call already closed");

        closeCalled = true;
        if (cancelled) {
            // No need to write anything to client if cancelled already.
            closeListener(status);
            return;
        }

        final HttpHeaders trailers = statusToTrailers(status, sendHeadersCalled);
        final HttpObject trailersObj;
        if (sendHeadersCalled && GrpcSerializationFormats.isGrpcWeb(serializationFormat)) {
            // Normal trailers are not supported in grpc-web and must be encoded as a message.
            // Message compression is not supported in grpc-web, so we don't bother using the normal
            // ArmeriaMessageFramer.
            trailersObj = serializeTrailersAsMessage(trailers);
        } else {
            trailersObj = trailers;
        }
        try {
            if (res.tryWrite(trailersObj)) {
                res.close();
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
        messageFramer.setMessageCompression(messageCompression);
        this.messageCompression = messageCompression;
    }

    @Override
    public synchronized void setCompression(String compressorName) {
        checkState(!sendHeadersCalled, "sendHeaders has been called");
        compressor = compressorRegistry.lookupCompressor(compressorName);
        checkArgument(compressor != null, "Unable to find compressor by name %s", compressorName);
        messageFramer.setCompressor(compressor);
    }

    @Override
    public MethodDescriptor<I, O> getMethodDescriptor() {
        return method;
    }

    @Override
    public void messageRead(ByteBufOrStream message) {

        final I request;

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
            if (message.buf() != null && !success) {
                message.buf().release();
            }
        }

        try {
            request = marshaller.deserializeRequest(message);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
            ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method, request), null);
        }

        if (unsafeWrapRequestBuffers && message.buf() != null) {
            GrpcUnsafeBufferUtil.storeBuffer(message.buf(), request, ctx);
        }

        if (useBlockingTaskExecutor) {
            ctx.blockingTaskExecutor().execute(() -> invokeOnMessage(request));
        } else {
            invokeOnMessage(request);
        }
    }

    private void invokeOnMessage(I request) {
        try (SafeCloseable ignored = ctx.push()) {
            listener.onMessage(request);
        } catch (Throwable t) {
            close(Status.fromThrowable(t), EMPTY_METADATA);
        }
    }

    @Override
    public void endOfStream() {
        setClientStreamClosed();
        if (!closeCalled) {
            if (!ctx.log().isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
                ctx.logBuilder().requestContent(GrpcLogUtil.rpcRequest(method), null);
            }

            if (useBlockingTaskExecutor) {
                ctx.blockingTaskExecutor().execute(this::invokeHalfClose);
            } else {
                invokeHalfClose();
            }
        }
    }

    private void invokeHalfClose() {
        try (SafeCloseable ignored = ctx.push()) {
            listener.onHalfClose();
        } catch (Throwable t) {
            close(Status.fromThrowable(t), EMPTY_METADATA);
        }
    }

    @Override
    public void transportReportStatus(Status status) {
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
            setClientStreamClosed();
            messageFramer.close();
            ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(newStatus, firstResponse), null);
            if (newStatus.isOk()) {
                if (useBlockingTaskExecutor) {
                    ctx.blockingTaskExecutor().execute(this::invokeOnComplete);
                } else {
                    invokeOnComplete();
                }
            } else {
                cancelled = true;
                if (useBlockingTaskExecutor) {
                    ctx.blockingTaskExecutor().execute(this::invokeOnCancel);
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
            listener.onComplete();
        } catch (Throwable t) {
            // This should not be possible with normal generated stubs which do not implement
            // onComplete, but is conceivable for a completely manually constructed stub.
            logger.warn("Error in gRPC onComplete handler.", t);
        }
    }

    private void invokeOnCancel() {
        try (SafeCloseable ignored = ctx.push()) {
            listener.onCancel();
        } catch (Throwable t) {
            if (!closeCalled) {
                // A custom error when dealing with client cancel or transport issues should be
                // returned. We have already closed the listener, so it will not receive any more
                // callbacks as designed.
                close(Status.fromThrowable(t), EMPTY_METADATA);
            }
        }
    }

    private void setClientStreamClosed() {
        if (!clientStreamClosed) {
            messageReader().cancel();
            clientStreamClosed = true;
        }
    }

    private HttpHeaders statusToTrailers(Status status, boolean headersSent) {
        return statusToTrailers(ctx, status, headersSent);
    }

    static HttpHeaders statusToTrailers(ServiceRequestContext ctx, Status status, boolean headersSent) {
        final HttpHeaders trailers;
        if (headersSent) {
            // Normal trailers.
            trailers = new DefaultHttpHeaders();
        } else {
            // Trailers only response
            trailers = new DefaultHttpHeaders(true, 3, true)
                    .status(HttpStatus.OK)
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto");
        }
        trailers.add(GrpcHeaderNames.GRPC_STATUS, Integer.toString(status.getCode().value()));

        if (status.getDescription() != null) {
            trailers.add(GrpcHeaderNames.GRPC_MESSAGE, StatusMessageEscaper.escape(status.getDescription()));
        }
        if (ctx.server().config().verboseResponses() && status.getCause() != null) {
            final ThrowableProto proto = GrpcStatus.serializeThrowable(status.getCause());
            trailers.add(GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN,
                         Base64.getEncoder().encodeToString(proto.toByteArray()));
        }

        return trailers;
    }

    HttpStreamReader messageReader() {
        return messageReader;
    }

    void setListener(Listener<I> listener) {
        checkState(this.listener == null, "listener already set");
        this.listener = requireNonNull(listener, "listener");
    }

    private HttpData serializeTrailersAsMessage(HttpHeaders trailers) {
        final ByteBuf serialized = ctx.alloc().buffer();
        boolean success = false;
        try {
            serialized.writeByte(TRAILERS_FRAME_HEADER);
            // Skip, we'll set this after serializing the headers.
            serialized.writeInt(0);
            for (Map.Entry<AsciiString, String> trailer : trailers) {
                encodeHeader(trailer.getKey(), trailer.getValue(), serialized);
            }
            final int messageSize = serialized.readableBytes() - 5;
            serialized.setInt(1, messageSize);
            success = true;
        } finally {
            if (!success) {
                serialized.release();
            }
        }
        return new ByteBufHttpData(serialized, true);
    }

    private static Decompressor clientDecompressor(HttpHeaders headers, DecompressorRegistry registry) {
        final String encoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
        if (encoding == null) {
            return Identity.NONE;
        }
        final Decompressor decompressor = registry.lookupDecompressor(encoding);
        return firstNonNull(decompressor, Identity.NONE);
    }

    // Copied from io.netty.handler.codec.http.HttpHeadersEncoder
    private static void encodeHeader(CharSequence name, CharSequence value, ByteBuf buf) {
        final int nameLen = name.length();
        final int valueLen = value.length();
        final int entryLen = nameLen + valueLen + 4;
        buf.ensureWritable(entryLen);
        int offset = buf.writerIndex();
        writeAscii(buf, offset, name, nameLen);
        offset += nameLen;
        buf.setByte(offset++, ':');
        buf.setByte(offset++, ' ');
        writeAscii(buf, offset, value, valueLen);
        offset += valueLen;
        buf.setByte(offset++, '\r');
        buf.setByte(offset++, '\n');
        buf.writerIndex(offset);
    }

    private static void writeAscii(ByteBuf buf, int offset, CharSequence value, int valueLen) {
        if (value instanceof AsciiString) {
            ByteBufUtil.copy((AsciiString) value, 0, buf, offset, valueLen);
        } else {
            writeCharSequence(buf, offset, value, valueLen);
        }
    }

    private static void writeCharSequence(ByteBuf buf, int offset, CharSequence value, int valueLen) {
        for (int i = 0; i < valueLen; ++i) {
            buf.setByte(offset++, c2b(value.charAt(i)));
        }
    }
}
