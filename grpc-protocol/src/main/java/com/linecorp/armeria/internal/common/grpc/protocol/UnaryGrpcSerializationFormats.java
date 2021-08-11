/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.common.grpc.protocol;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * {@link SerializationFormat} instances supported by unary gRPC service and client that do not
 * depend on gRPC stubs.
 */
@UnstableApi
public final class UnaryGrpcSerializationFormats {

    /**
     * gRPC protobuf serialization format.
     */
    public static final SerializationFormat PROTO = SerializationFormat.of("gproto");

    /**
     * gRPC-Web protobuf serialization format.
     */
    public static final SerializationFormat PROTO_WEB = SerializationFormat.of("gproto-web");

    /**
     * gRPC-web-text protobuf serialization format.
     */
    public static final SerializationFormat PROTO_WEB_TEXT = SerializationFormat.of("gproto-web-text");

    private static final Set<SerializationFormat> FORMATS = ImmutableSet.of(
            PROTO, PROTO_WEB, PROTO_WEB_TEXT);

    /**
     * Returns the set of all known gRPC serialization formats.
     */
    public static Set<SerializationFormat> values() {
        return FORMATS;
    }

    /**
     * Returns whether the specified {@link SerializationFormat} is gRPC.
     */
    public static boolean isGrpc(SerializationFormat format) {
        return FORMATS.contains(requireNonNull(format, "format"));
    }

    /**
     * Returns whether the specified {@link SerializationFormat} is gRPC-Web, the subset of gRPC that supports
     * browsers.
     */
    public static boolean isGrpcWeb(SerializationFormat format) {
        requireNonNull(format, "format");
        return format == PROTO_WEB || format == PROTO_WEB_TEXT;
    }

    /**
     * Returns whether the specified {@link SerializationFormat} is gRPC-web-text which encodes messages
     * using base64.
     */
    public static boolean isGrpcWebText(SerializationFormat format) {
        requireNonNull(format, "format");
        return format == PROTO_WEB_TEXT;
    }

    private UnaryGrpcSerializationFormats() {}
}
