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

package com.linecorp.armeria.internal.grpc;

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

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.CompositeByteBuf;

/**
 * Marshaller for gRPC method request or response messages to and from {@link ByteBuf}. Will attempt to use
 * optimized code paths for known message types, and otherwise delegates to the gRPC stub.
 */
public class GrpcMessageMarshaller<I, O> {

    private enum MessageType {
        UNKNOWN,
        PROTOBUF
    }

    private final ByteBufAllocator alloc;
    private final SerializationFormat serializationFormat;
    private final MethodDescriptor<I, O> method;
    private final MessageMarshaller jsonMarshaller;
    private final MessageType requestType;
    private final MessageType responseType;

    public GrpcMessageMarshaller(ByteBufAllocator alloc,
                                 SerializationFormat serializationFormat,
                                 MethodDescriptor<I, O> method,
                                 @Nullable MessageMarshaller jsonMarshaller) {
        this.alloc = requireNonNull(alloc, "alloc");
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        this.method = requireNonNull(method, "method");
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
                CompositeByteBuf out = alloc.compositeBuffer();
                try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
                    ByteStreams.copy(method.streamRequest(message), os);
                }
                return out;
        }
    }

    public I deserializeRequest(ByteBufOrStream message) throws IOException {
        InputStream messageStream = message.stream();
        if (message.buf() != null) {
            try {
                switch (requestType) {
                    case PROTOBUF:
                        PrototypeMarshaller<I> marshaller =
                                (PrototypeMarshaller<I>) method.getRequestMarshaller();
                        // PrototypeMarshaller<I>.getMessagePrototype will always parse to I
                        @SuppressWarnings("unchecked")
                        I msg = (I) deserializeProto(message.buf(), (Message) marshaller.getMessagePrototype());
                        return msg;
                    default:
                        // Fallback to using the method's stream marshaller.
                        messageStream = new ByteBufInputStream(message.buf().retain(), true);
                        break;
                }
            } finally {
                message.buf().release();
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
                CompositeByteBuf out = alloc.compositeBuffer();
                try (ByteBufOutputStream os = new ByteBufOutputStream(out)) {
                    ByteStreams.copy(method.streamResponse(message), os);
                }
                return out;
        }
    }

    public O deserializeResponse(ByteBufOrStream message) throws IOException {
        InputStream messageStream = message.stream();
        if (message.buf() != null) {
            try {
                switch (responseType) {
                    case PROTOBUF:
                        PrototypeMarshaller<O> marshaller =
                                (PrototypeMarshaller<O>) method.getResponseMarshaller();
                        // PrototypeMarshaller<I>.getMessagePrototype will always parse to I
                        @SuppressWarnings("unchecked")
                        O msg = (O) deserializeProto(message.buf(), (Message) marshaller.getMessagePrototype());
                        return msg;
                    default:
                        // Fallback to using the method's stream marshaller.
                        messageStream = new ByteBufInputStream(message.buf().retain(), true);
                        break;
                }
            } finally {
                message.buf().release();
            }
        }
        try (InputStream msg = messageStream) {
            return method.parseResponse(msg);
        }
    }

    private ByteBuf serializeProto(Message message) throws IOException {
        if (GrpcSerializationFormats.isProto(serializationFormat)) {
            ByteBuf buf = alloc.buffer(message.getSerializedSize());
            boolean success = false;
            try {
                message.writeTo(CodedOutputStream.newInstance(buf.nioBuffer(0, buf.writableBytes())));
                buf.writerIndex(buf.capacity());
                success = true;
            } finally {
                if (!success) {
                    buf.release();
                }
            }
            return buf;
        } else if (GrpcSerializationFormats.isJson(serializationFormat)) {
            ByteBuf buf = alloc.buffer();
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
            CodedInputStream stream = CodedInputStream.newInstance(buf.nioBuffer());
            try {
                Message msg = prototype.getParserForType().parseFrom(stream);
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
        } else if (GrpcSerializationFormats.isJson(serializationFormat)) {
            Message.Builder builder = prototype.newBuilderForType();
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
