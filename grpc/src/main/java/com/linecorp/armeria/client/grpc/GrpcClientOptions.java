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

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

/**
 * {@link ClientOption}s to control gRPC-specific behavior.
 */
public final class GrpcClientOptions {

    /**
     * The maximum size, in bytes, of messages coming in a response.
     */
    public static final ClientOption<Integer> MAX_INBOUND_MESSAGE_SIZE_BYTES = ClientOption.valueOf(
            "MAX_INBOUND_MESSAGE_SIZE_BYTES");

    /**
     * The maximum size, in bytes, of messages sent in a request.
     */
    public static final ClientOption<Integer> MAX_OUTBOUND_MESSAGE_SIZE_BYTES = ClientOption.valueOf(
            "MAX_OUTBOUND_MESSAGE_SIZE_BYTES");

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
            ClientOption.valueOf("UNSAFE_WRAP_RESPONSE_BUFFERS");

    private GrpcClientOptions() {}
}
