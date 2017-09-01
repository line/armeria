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

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;

public final class GrpcHeaderNames {

    public static final AsciiString GRPC_STATUS = HttpHeaderNames.of("grpc-status");

    public static final AsciiString GRPC_MESSAGE = HttpHeaderNames.of("grpc-message");

    public static final AsciiString GRPC_ENCODING = HttpHeaderNames.of("grpc-encoding");

    public static final AsciiString GRPC_ACCEPT_ENCODING = HttpHeaderNames.of("grpc-accept-encoding");

    public static final AsciiString GRPC_TIMEOUT = HttpHeaderNames.of("grpc-timeout");

    private GrpcHeaderNames() {}
}
