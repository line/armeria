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

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.common.io.ByteStreams;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnsafeByteOperations;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;

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
    private final SerializationFormat serializationFormat;
    private final MethodDescriptor<I, O> method;
    @Nullable
    private final MessageMarshaller jsonMarshaller;
    private final MessageType requestType;
    private final MessageType responseType;
    private final boolean unsafeWrapDeserializedBuffer;

    public GrpcMessageMarshaller(ByteBufAllocator alloc,
                                 SerializationFormat serializationFormat,
                                 MethodDescriptor<I, O> method,
                                 @Nullable MessageMarshaller jsonMarshaller,
                                 boolean unsafeWrapDeserializedBuffer) {
        this.alloc = requireNonNull(alloc, "alloc");
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        this.method = requireNonNull(method, "method");
        this.unsafeWrapDeserializedBuffer = unsafeWrapDeserializedBuffer;
        checkArgument(!GrpcSerializationFormats.isJson(serializationFormat) || jsonMarshaller != null,
                      "jsonMarshaller must be non-null when serializationFormat is JSON.");
        this.jsonMarshaller = jsonMarshaller;
        requestType = marshallerType(method.getRequestMarshaller());
        responseType = marshallerType(method.getResponseMarshaller());
    }

    public ByteBuf serializeRequest(I message) throws IOException {
        switch (requestType) {
            case PROTOBUF:
                return serializeProto((Message) message);
            default:
                final CompositeByteBuf out = alloc.compositeBuffer();
                try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
                    ByteStreams.copy(method.streamRequest(message), os);
                }
                return out;
        }
    }

    public I deserializeRequest(DeframedMessage message) throws IOException {
        InputStream messageStream = message.stream();
        if (message.buf() != null) {
            try {
                switch (requestType) {
                    case PROTOBUF:
                        final PrototypeMarshaller<I> marshaller =
                                (PrototypeMarshaller<I>) method.getRequestMarshaller();
                        // PrototypeMarshaller<I>.getMessagePrototype will always parse to I
                        @SuppressWarnings("unchecked")
                        final I msg = (I) deserializeProto(message.buf(),
                                                           (Message) marshaller.getMessagePrototype());
                        return msg;
                    default:
                        // Fallback to using the method's stream marshaller.
                        messageStream = new ByteBufInputStream(message.buf().retain(), true);
                        break;
                }
            } finally {
                if (!unsafeWrapDeserializedBuffer) {
                    message.buf().release();
                }
            }
        }
        try (InputStream msg = messageStream) {
            return method.parseRequest(msg);
        }
    }

    public ByteBuf serializeResponse(O message) throws IOException {
        switch (responseType) {
            case PROTOBUF:
                return serializeProto((Message) message);
            default:
                final CompositeByteBuf out = alloc.compositeBuffer();
                try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
                    ByteStreams.copy(method.streamResponse(message), os);
                }
                return out;
        }
    }

    public O deserializeResponse(DeframedMessage message) throws IOException {
        InputStream messageStream = message.stream();
        if (message.buf() != null) {
            try {
                switch (responseType) {
                    case PROTOBUF:
                        final PrototypeMarshaller<O> marshaller =
                                (PrototypeMarshaller<O>) method.getResponseMarshaller();
                        // PrototypeMarshaller<I>.getMessagePrototype will always parse to I
                        @SuppressWarnings("unchecked")
                        final O msg = (O) deserializeProto(message.buf(),
                                                           (Message) marshaller.getMessagePrototype());
                        return msg;
                    default:
                        // Fallback to using the method's stream marshaller.
                        messageStream = new ByteBufInputStream(message.buf().retain(), true);
                        break;
                }
            } finally {
                if (!unsafeWrapDeserializedBuffer) {
                    message.buf().release();
                }
            }
        }
        try (InputStream msg = messageStream) {
            return method.parseResponse(msg);
        }
    }

    private ByteBuf serializeProto(Message message) throws IOException {
        if (GrpcSerializationFormats.isProto(serializationFormat)) {
            final int serializedSize = message.getSerializedSize();
            if (serializedSize == 0) {
                return Unpooled.EMPTY_BUFFER;
            }
            final ByteBuf buf = alloc.buffer(serializedSize);
            boolean success = false;
            try {
                message.writeTo(CodedOutputStream.newInstance(buf.nioBuffer(0, serializedSize)));
                buf.writerIndex(serializedSize);
                success = true;
            } finally {
                if (!success) {
                    buf.release();
                }
            }
            return buf;
        }

        if (GrpcSerializationFormats.isJson(serializationFormat)) {
            final ByteBuf buf = alloc.buffer();
            boolean success = false;
            try (ByteBufOutputStream os = new ByteBufOutputStream(buf)) {
                jsonMarshaller.writeValue(message, os);
                success = true;
            } finally {
                if (!success) {
                    buf.release();
                }
            }
            return buf;
        }
        throw new IllegalStateException("Unknown serialization format: " + serializationFormat);
    }

    private Message deserializeProto(ByteBuf buf, Message prototype) throws IOException {
        if (GrpcSerializationFormats.isProto(serializationFormat)) {
            if (!buf.isReadable()) {
                return prototype.getDefaultInstanceForType();
            }
            final CodedInputStream stream;
            if (unsafeWrapDeserializedBuffer) {
                stream = UnsafeByteOperations.unsafeWrap(buf.nioBuffer()).newCodedInput();
                stream.enableAliasing(true);
            } else {
                stream = CodedInputStream.newInstance(buf.nioBuffer());
            }
            try {
                final Message msg = prototype.getParserForType().parseFrom(stream);
                try {
                    stream.checkLastTagWas(0);
                } catch (InvalidProtocolBufferException e) {
                    e.setUnfinishedMessage(msg);
                    throw e;
                }
                return msg;
            } catch (InvalidProtocolBufferException e) {
                throw Status.INTERNAL.withDescription("Invalid protobuf byte sequence")
                                     .withCause(e).asRuntimeException();
            }
        }

        if (GrpcSerializationFormats.isJson(serializationFormat)) {
            final Message.Builder builder = prototype.newBuilderForType();
            try (ByteBufInputStream is = new ByteBufInputStream(buf, /* releaseOnClose */ false)) {
                jsonMarshaller.mergeValue(is, builder);
            }
            return builder.build();
        }
        throw new IllegalStateException("Unknown serialization format: " + serializationFormat);
    }

    private static MessageType marshallerType(Marshaller<?> marshaller) {
        return marshaller instanceof PrototypeMarshaller ? MessageType.PROTOBUF : MessageType.UNKNOWN;
    }
}
