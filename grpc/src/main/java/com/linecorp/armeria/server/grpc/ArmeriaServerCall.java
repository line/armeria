/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.ErrorListener;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.StatusMessageEscaper;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Codec;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.CompositeByteBuf;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link ServerCall.Listener}, and writing messages passed back to the response.
 */
class ArmeriaServerCall<I, O> extends ServerCall<I, O>
        implements ArmeriaMessageDeframer.Listener, ErrorListener {

    // TODO(anuraag): Support json too.
    private static final String GRPC_PROTO_CONTENT_TYPE = "application/grpc+proto";

    private static final Metadata EMPTY_METADATA = new Metadata();

    private static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();

    private final MethodDescriptor<I, O> method;

    private final HttpStreamReader messageReader;
    private final ArmeriaMessageFramer messageFramer;

    private final HttpResponseWriter res;
    private final CompressorRegistry compressorRegistry;
    private final DecompressorRegistry decompressorRegistry;
    private final ByteBufAllocator alloc;
    private final ServiceRequestContext ctx;

    // Only set once.
    private ServerCall.Listener<I> listener;

    @Nullable private final String clientAcceptEncoding;

    private Compressor compressor;
    private boolean messageCompression;
    private boolean messageReceived;

    // state
    private volatile boolean cancelled;
    private volatile boolean clientStreamClosed;
    private boolean sendHeadersCalled;
    private boolean closeCalled;

    ArmeriaServerCall(HttpHeaders clientHeaders,
                      MethodDescriptor<I, O> method,
                      CompressorRegistry compressorRegistry,
                      DecompressorRegistry decompressorRegistry,
                      ByteBufAllocator alloc,
                      HttpResponseWriter res,
                      int maxInboundMessageSizeBytes,
                      int maxOutboundMessageSizeBytes,
                      ServiceRequestContext ctx) {
        this.method = method;
        this.ctx = ctx;
        this.messageReader = new HttpStreamReader(
                new ArmeriaMessageDeframer(
                        this,
                        clientDecompressor(clientHeaders, decompressorRegistry),
                        maxInboundMessageSizeBytes,
                        alloc),
                this);
        this.messageFramer = new ArmeriaMessageFramer(alloc, maxOutboundMessageSizeBytes);
        this.res = res;
        this.compressorRegistry = compressorRegistry;
        this.clientAcceptEncoding =
                Strings.emptyToNull(clientHeaders.get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING));
        this.decompressorRegistry = decompressorRegistry;
        this.alloc = alloc;
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
        checkState(!sendHeadersCalled, "sendHeaders already called");
        checkState(!closeCalled, "call is closed");

        HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);

        headers.set(HttpHeaderNames.CONTENT_TYPE, GRPC_PROTO_CONTENT_TYPE);

        if (compressor == null || !messageCompression || clientAcceptEncoding == null) {
            compressor = Codec.Identity.NONE;
        } else {
            List<String> acceptedEncodingsList =
                    ACCEPT_ENCODING_SPLITTER.splitToList(clientAcceptEncoding);
            if (!acceptedEncodingsList.contains(compressor.getMessageEncoding())) {
                // resort to using no compression.
                compressor = Codec.Identity.NONE;
            }
        }
        messageFramer.setCompressor(compressor);

        // Always put compressor, even if it's identity.
        headers.add(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());

        String advertisedEncodings = decompressorRegistry.getRawAdvertisedMessageEncodings();
        if (!advertisedEncodings.isEmpty()) {
            headers.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodings);
        }

        sendHeadersCalled = true;
        res.write(headers);
    }

    @Override
    public void sendMessage(O message) {
        checkState(sendHeadersCalled, "sendHeaders has not been called");
        checkState(!closeCalled, "call is closed");

        try {
            res.write(messageFramer.writePayload(serializeMessage(message)));
        } catch (RuntimeException e) {
            close(Status.fromThrowable(e));
            throw e;
        } catch (Throwable t) {
            close(Status.fromThrowable(t));
            throw new RuntimeException(t);
        }

        // We don't have a good way of listening to actual stream writes, and this functionality
        // probably isn't used in most applications anyways, so just say we're always ready for more messages.
        // In cases where flow control is activated, this may result in excessive internal buffering.
        final boolean notifyReady = isReady();
        if (notifyReady) {
            listener.onReady();
        }
    }

    @Override
    public boolean isReady() {
        return !closeCalled;
    }

    void close(Status status) {
        close(status, EMPTY_METADATA);
    }

    @Override
    public void close(Status status, Metadata unusedGrpcMetadata) {
        checkState(!closeCalled, "call already closed");

        messageFramer.close();
        if (!clientStreamClosed) {
            messageReader.cancel();
        }

        res.write(statusToTrailers(status, sendHeadersCalled));
        res.close();

        closeCalled = true;

        if (status.isOk()) {
            listener.onComplete();
        } else {
            cancelled = true;
            listener.onCancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setMessageCompression(boolean messageCompression) {
        messageFramer.setMessageCompression(messageCompression);
        this.messageCompression = messageCompression;
    }

    @Override
    public void setCompression(String compressorName) {
        checkState(!sendHeadersCalled, "sendHeaders has been called");
        compressor =  compressorRegistry.lookupCompressor(compressorName);
        checkArgument(compressor != null, "Unable to find compressor by name %s", compressorName);
        messageFramer.setCompressor(compressor);
    }

    @Override
    public MethodDescriptor<I, O> getMethodDescriptor() {
        return method;
    }

    @Override
    public void messageRead(ByteBufOrStream message) {
        // Special case for unary calls.
        if (messageReceived && method.getType() == MethodType.UNARY) {
            close(Status.INTERNAL.withDescription(
                    "More than one request messages for unary call or server streaming call"),
                  new Metadata());
            return;
        }
        messageReceived = true;

        if (message.buf() != null) {
            try {
                if (isCancelled()) {
                    return;
                }
                if (method.getRequestMarshaller() instanceof PrototypeMarshaller) {
                    // Special case to parsing a protobuf from a ByteBuffer, which is highly optimized.
                    PrototypeMarshaller<? extends MessageLite> marshaller =
                            (PrototypeMarshaller<? extends MessageLite>) method.getRequestMarshaller();
                    CodedInputStream stream = CodedInputStream.newInstance(message.buf().nioBuffer());
                    try {
                        MessageLite msg = marshaller.getMessagePrototype().getParserForType().parseFrom(stream);
                        try {
                            stream.checkLastTagWas(0);
                        } catch (InvalidProtocolBufferException e) {
                            e.setUnfinishedMessage(msg);
                            throw e;
                        }
                        @SuppressWarnings("unchecked")  // We used the request marshaller so always safe.
                        I castMsg = (I) msg;
                        listener.onMessage(castMsg);
                    } catch (InvalidProtocolBufferException e) {
                        throw Status.INTERNAL.withDescription("Invalid protobuf byte sequence")
                                             .withCause(e).asRuntimeException();
                    }
                }
            } finally {
                message.buf().release();
            }
        } else {
            try (InputStream msg = message.stream()) {
                if (isCancelled()) {
                    return;
                }
                listener.onMessage(method.parseRequest(msg));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void endOfStream() {
        clientStreamClosed = true;
        if (!closeCalled) {
            listener.onHalfClose();
        }
    }

    @Override
    public void onError(Status status) {
        close(status);
    }

    static HttpHeaders statusToTrailers(Status status, boolean headersSent) {
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
            trailers.add(GrpcHeaderNames.GRPC_MESSAGE,
                         StatusMessageEscaper.escape(status.getDescription()));
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

    private ByteBuf serializeMessage(O message) throws IOException {
        if (message instanceof MessageLite) {
            MessageLite msg = (MessageLite) message;
            // Special case serializing a protobuf to a ByteBuffer, which is highly optimized.
            ByteBuf buf = alloc.buffer(msg.getSerializedSize());
            msg.writeTo(CodedOutputStream.newInstance(buf.nioBuffer(0, buf.writableBytes())));
            buf.writerIndex(buf.capacity());
            return buf;
        }
        CompositeByteBuf out = alloc.compositeBuffer();
        try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
            ByteStreams.copy(method.streamResponse(message), os);
        }
        return out;
    }

    private static Decompressor clientDecompressor(HttpHeaders headers, DecompressorRegistry registry) {
        String encoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
        if (encoding == null) {
            return Identity.NONE;
        }
        Decompressor decompressor = registry.lookupDecompressor(encoding);
        return firstNonNull(decompressor, Identity.NONE);
    }
}
