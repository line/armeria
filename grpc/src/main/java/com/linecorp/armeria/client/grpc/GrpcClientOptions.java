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

package com.linecorp.armeria.client.grpc;

import java.util.function.Consumer;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.common.grpc.NoopJsonMarshaller;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

/**
 * {@link ClientOption}s to control gRPC-specific behavior.
 */
public final class GrpcClientOptions {

    /**
     * The maximum size, in bytes, of messages coming in a response.
     * The default value is {@value ArmeriaMessageDeframer#NO_MAX_INBOUND_MESSAGE_SIZE},
     * which means 'use {@link ClientOption#MAX_RESPONSE_LENGTH}'.
     */
    public static final ClientOption<Integer> MAX_INBOUND_MESSAGE_SIZE_BYTES =
            ClientOption.define("GRPC_MAX_INBOUND_MESSAGE_SIZE_BYTES",
                                ArmeriaMessageDeframer.NO_MAX_INBOUND_MESSAGE_SIZE);

    /**
     * The maximum size, in bytes, of messages sent in a request.
     * The default value is {@value ArmeriaMessageFramer#NO_MAX_OUTBOUND_MESSAGE_SIZE},
     * which means unlimited.
     */
    public static final ClientOption<Integer> MAX_OUTBOUND_MESSAGE_SIZE_BYTES =
            ClientOption.define("GRPC_MAX_OUTBOUND_MESSAGE_SIZE_BYTES",
                                ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE);

    /**
     * Enables unsafe retention of response buffers. Can improve performance when working with very large
     * (i.e., several megabytes) payloads.
     *
     * <p><strong>DISCLAIMER:</strong> Do not use this if you don't know what you are doing. It is very easy to
     * introduce memory leaks when using this method. You will probably spend much time debugging memory leaks
     * during development if this is enabled. You will probably spend much time debugging memory leaks in
     * production if this is enabled. You probably don't want to do this and should turn back now.
     *
     * <p>When enabled, the reference-counted buffer received from the server will be stored into
     * {@link RequestContext} instead of being released. All {@link ByteString} in a
     * protobuf message will reference sections of this buffer instead of having their own copies. When done
     * with a response message, call {@link GrpcUnsafeBufferUtil#releaseBuffer(Object, RequestContext)}
     * with the message and the request's context to release the buffer. The message must be the same
     * reference as what was passed to the client stub - a message with the same contents will not
     * work. If {@link GrpcUnsafeBufferUtil#releaseBuffer(Object, RequestContext)} is not called, the memory
     * will be leaked.
     *
     * <p>Due to the limited lifetime of {@link RequestContext} for blocking and async clients, this option
     * is only really useful in conjunction with streaming clients. Even when using unary methods, it is
     * recommended to use a streaming stub for easy access to the {@link RequestContext}.
     */
    public static final ClientOption<Boolean> UNSAFE_WRAP_RESPONSE_BUFFERS =
            ClientOption.define("GRPC_UNSAFE_WRAP_RESPONSE_BUFFERS", false);

    /**
     * Sets a {@link GrpcJsonMarshaller} that serializes and deserializes request or response messages
     * to and from JSON depending on {@link SerializationFormat}. This replaces the built-in
     * {@link GrpcJsonMarshaller} with the specified {@link GrpcJsonMarshaller}.
     *
     * <p>This is commonly used to marshall non-{@link Message} types such as {@code scalapb.GeneratedMessage}
     * for Scala and {@code pbandk.Message} for Kotlin.
     *
     * <p>Note that {@link #JSON_MARSHALLER_CUSTOMIZER} option will be ignored if this option is set.
     */
    public static final ClientOption<GrpcJsonMarshaller> GRPC_JSON_MARSHALLER =
            ClientOption.define("GRPC_JSON_MARSHALLER", NoopJsonMarshaller.get());

    /**
     * Sets a {@link Consumer} that can customize the JSON marshaller for {@link Message} used when handling
     * JSON payloads in the service. This is commonly used to switch from the default of using lowerCamelCase
     * for field names to using the field name from the proto definition, by setting
     * {@link MessageMarshaller.Builder#preservingProtoFieldNames(boolean)}.
     *
     * <p>Note that this option will be ignored if {@link #GRPC_JSON_MARSHALLER} is set.
     */
    public static final ClientOption<Consumer<MessageMarshaller.Builder>> JSON_MARSHALLER_CUSTOMIZER =
            ClientOption.define("GRPC_JSON_MARSHALLER_CUSTOMIZER", unused -> { /* no-op */ });

    private GrpcClientOptions() {}
}
