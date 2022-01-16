/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.grpc;

/**
 * Represents the type of {@link GrpcJsonMarshaller}.
 */
public enum GrpcJsonMarshallType {
    /**
     * Use <a href=https://github.com/curioswitch/protobuf-jackson>protobuf-jackson</a>
     * for more efficient json marshalling.
     */
    PROTOBUF_JACKSON,
    /**
     * Use upstream google's implementation for json marshalling which also partially supports {@code proto2}.
     */
    UPSTREAM,
    /**
     * The best of both worlds. Use {@link GrpcJsonMarshallType#PROTOBUF_JACKSON} by default,
     * and try to use {@link GrpcJsonMarshallType#UPSTREAM} if the message type is {@code proto2}.
     */
    DEFAULT,
}
