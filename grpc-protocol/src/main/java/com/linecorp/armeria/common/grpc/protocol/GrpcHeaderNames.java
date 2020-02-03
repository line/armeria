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

package com.linecorp.armeria.common.grpc.protocol;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.util.AsciiString;

/**
 * gRPC-related HTTP header names.
 */
@UnstableApi
public final class GrpcHeaderNames {
    /**
     * {@code "grpc-status"}.
     */
    public static final AsciiString GRPC_STATUS = HttpHeaderNames.of("grpc-status");
    /**
     * {@code "grpc-message"}.
     */
    public static final AsciiString GRPC_MESSAGE = HttpHeaderNames.of("grpc-message");
    /**
     * {@code "grpc-encoding"}.
     */
    public static final AsciiString GRPC_ENCODING = HttpHeaderNames.of("grpc-encoding");
    /**
     * {@code "grpc-accept-encoding"}.
     */
    public static final AsciiString GRPC_ACCEPT_ENCODING = HttpHeaderNames.of("grpc-accept-encoding");
    /**
     * {@code "grpc-timeout"}.
     */
    public static final AsciiString GRPC_TIMEOUT = HttpHeaderNames.of("grpc-timeout");
    /**
     * {@code "armeria.grpc.ThrowableProto-bin"}.
     */
    public static final AsciiString ARMERIA_GRPC_THROWABLEPROTO_BIN =
            HttpHeaderNames.of("armeria.grpc.ThrowableProto-bin");

    private GrpcHeaderNames() {}
}
