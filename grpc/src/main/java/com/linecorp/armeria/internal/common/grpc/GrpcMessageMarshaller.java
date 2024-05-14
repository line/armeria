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

package com.linecorp.armeria.internal.common.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;

import com.google.common.io.ByteStreams;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnsafeByteOperations;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Marshaller for gRPC method request or response messages to and from {@link ByteBuf}. Will attempt to use
 * optimized code paths for known message types, and otherwise delegates to the gRPC stub.
 */
public final class GrpcMessageMarshaller<I, O> {

    private enum MessageType {
        UNKNOWN,
        PROTOBUF
    }

    private final ByteBufAllocator alloc;
    private final MethodDescriptor<I, O> method;
    @Nullable
    private final GrpcJsonMarshaller jsonMarshaller;
    private final Marshaller<I> requestMarshaller;
    private final Marshaller<O> responseMarshaller;
    private final MessageType requestType;
    private final MessageType responseType;
    private final boolean unsafeWrapDeserializedBuffer;
    private final boolean isProto;
    private final boolean useMethodMarshaller;

    public GrpcMessageMarshaller(ByteBufAllocator alloc,
                                 SerializationFormat serializationFormat,
                                 MethodDescriptor<I, O> method,
                                 @Nullable GrpcJsonMarshaller jsonMarshaller,
                                 boolean unsafeWrapDeserializedBuffer,
                                 boolean useMethodMarshaller) {
        this.alloc = requireNonNull(alloc, "alloc");
        this.method = requireNonNull(method, "method");
        this.unsafeWrapDeserializedBuffer = unsafeWrapDeserializedBuffer;
        checkArgument(!GrpcSerializationFormats.isJson(serializationFormat) || jsonMarshaller != null,
                      "jsonMarshaller must be non-null when serializationFormat is JSON.");
        isProto = GrpcSerializationFormats.isProto(serializationFormat);
        this.jsonMarshaller = jsonMarshaller;
        requestMarshaller = method.getRequestMarshaller();
        responseMarshaller = method.getResponseMarshaller();
        requestType = marshallerType(requestMarshaller);
        responseType = marshallerType(responseMarshaller);
        this.useMethodMarshaller = useMethodMarshaller;
    }

    public ByteBuf serializeRequest(I message) throws IOException {
        switch (requestType) {
            case PROTOBUF:
                final PrototypeMarshaller<I> marshaller = (PrototypeMarshaller<I>) requestMarshaller;
                return serializeProto(marshaller, (Message) message);
            default:
                // TODO(minwoox) Optimize this by creating buffer with the sensible initial capacity.
                final CompositeByteBuf out = alloc.compositeBuffer();
                try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
                    if (isProto) {
                        try (InputStream is = method.streamRequest(message)) {
                            ByteStreams.copy(is, os);
                        }
                    } else {
                        jsonMarshaller.serializeMessage(requestMarshaller, message, os);
                    }
                }
                return out;
        }
    }

    public I deserializeRequest(DeframedMessage message, boolean grpcWebText) throws IOException {
        InputStream messageStream = message.stream();
        final ByteBuf buf = message.buf();
        if (buf != null) {
            try {
                switch (requestType) {
                    case PROTOBUF:
                        final PrototypeMarshaller<I> marshaller = (PrototypeMarshaller<I>) requestMarshaller;
                        // PrototypeMarshaller<I>.getMessagePrototype will always parse to I
                        @SuppressWarnings("unchecked")
                        final I msg = (I) deserializeProto(marshaller, buf);
                        return msg;
                    default:
                        // Fallback to using the method's stream marshaller.
                        messageStream = new ByteBufInputStream(buf.retain(), true);
                        break;
                }
            } finally {
                if (!unsafeWrapDeserializedBuffer || grpcWebText) {
                    buf.release();
                }
            }
        }
        try (InputStream msg = messageStream) {
            if (isProto) {
                return method.parseRequest(msg);
            } else {
                return jsonMarshaller.deserializeMessage(requestMarshaller, msg);
            }
        }
    }

    public ByteBuf serializeResponse(O message) throws IOException {
        switch (responseType) {
            case PROTOBUF:
                final PrototypeMarshaller<O> marshaller =
                        (PrototypeMarshaller<O>) method.getResponseMarshaller();
                return serializeProto(marshaller, (Message) message);
            default:
                // TODO(minwoox) Optimize this by creating buffer with the sensible initial capacity.
                final CompositeByteBuf out = alloc.compositeBuffer();
                try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
                    if (isProto) {
                        try (InputStream is = method.streamResponse(message)) {
                            ByteStreams.copy(is, os);
                        }
                    } else {
                        jsonMarshaller.serializeMessage(responseMarshaller, message, os);
                    }
                }
                return out;
        }
    }

    public O deserializeResponse(DeframedMessage message, boolean grpcWebText) throws IOException {
        InputStream messageStream = message.stream();
        final ByteBuf buf = message.buf();
        if (buf != null) {
            try {
                switch (responseType) {
                    case PROTOBUF:
                        final PrototypeMarshaller<O> marshaller =
                                (PrototypeMarshaller<O>) method.getResponseMarshaller();
                        // PrototypeMarshaller<I>.getMessagePrototype will always parse to I
                        @SuppressWarnings("unchecked")
                        final O msg = (O) deserializeProto(marshaller, buf);
                        return msg;
                    default:
                        // Fallback to using the method's stream marshaller.
                        messageStream = new ByteBufInputStream(buf.retain(), true);
                        break;
                }
            } finally {
                if (!unsafeWrapDeserializedBuffer || grpcWebText) {
                    buf.release();
                }
            }
        }
        try (InputStream msg = messageStream) {
            if (isProto) {
                return method.parseResponse(msg);
            } else {
                return jsonMarshaller.deserializeMessage(responseMarshaller, msg);
            }
        }
    }

    private <T> ByteBuf serializeProto(PrototypeMarshaller<T> marshaller, Message message) throws IOException {
        if (isProto) {
            final int serializedSize = message.getSerializedSize();
            if (serializedSize == 0) {
                return Unpooled.EMPTY_BUFFER;
            }
            final ByteBuf buf = alloc.buffer(serializedSize);
            boolean success = false;
            try {
                if (useMethodMarshaller) {
                    final InputStream is = marshaller.stream((T) message);
                    try (ByteBufOutputStream os = new ByteBufOutputStream(buf)) {
                        ByteStreams.copy(is, os);
                    } finally {
                        is.close();
                    }
                } else {
                    message.writeTo(CodedOutputStream.newInstance(buf.nioBuffer(0, serializedSize)));
                    buf.writerIndex(serializedSize);
                }
                success = true;
            } finally {
                if (!success) {
                    buf.release();
                }
            }
            return buf;
        } else {
            // TODO(minwoox) Optimize this by creating buffer with the sensible initial capacity.
            final ByteBuf buf = alloc.compositeBuffer();
            boolean success = false;
            try (ByteBufOutputStream os = new ByteBufOutputStream(buf)) {
                @SuppressWarnings("unchecked")
                final T cast = (T) message;
                jsonMarshaller.serializeMessage(marshaller, cast, os);
                success = true;
            } finally {
                if (!success) {
                    buf.release();
                }
            }
            return buf;
        }
    }

    private <T> Message deserializeProto(PrototypeMarshaller<T> marshaller, ByteBuf buf) throws IOException {
        final Message prototype = (Message) marshaller.getMessagePrototype();
        if (isProto) {
            if (!buf.isReadable()) {
                return prototype.getDefaultInstanceForType();
            }
            try {
                final Message msg;
                if (useMethodMarshaller) {
                    msg = (Message) marshaller.parse(new ByteBufInputStream(buf));
                } else {
                    final CodedInputStream stream;
                    if (unsafeWrapDeserializedBuffer) {
                        stream = UnsafeByteOperations.unsafeWrap(buf.nioBuffer()).newCodedInput();
                        stream.enableAliasing(true);
                    } else {
                        stream = CodedInputStream.newInstance(buf.nioBuffer());
                    }
                    msg = prototype.getParserForType().parseFrom(stream);
                    try {
                        stream.checkLastTagWas(0);
                    } catch (InvalidProtocolBufferException e) {
                        e.setUnfinishedMessage(msg);
                        throw e;
                    }
                }
                return msg;
            } catch (InvalidProtocolBufferException e) {
                throw Status.INTERNAL.withDescription("Invalid protobuf byte sequence")
                                     .withCause(e).asRuntimeException();
            }
        } else {
            try (ByteBufInputStream is = new ByteBufInputStream(buf, /* releaseOnClose */ false)) {
                return (Message) jsonMarshaller.deserializeMessage(marshaller, is);
            }
        }
    }

    private static MessageType marshallerType(Marshaller<?> marshaller) {
        return marshaller instanceof PrototypeMarshaller ? MessageType.PROTOBUF : MessageType.UNKNOWN;
    }
}
