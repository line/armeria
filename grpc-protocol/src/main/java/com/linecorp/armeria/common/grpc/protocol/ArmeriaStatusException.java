/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link Exception} that contains enough information to convert it to a gRPC status.
 */
@UnstableApi
public final class ArmeriaStatusException extends RuntimeException {

    private static final long serialVersionUID = -8370257107063108923L;

    private final int code;

    @Nullable
    private final byte[] grpcStatusDetailsBin;

    /**
     * Constructs an {@link ArmeriaStatusException} for the given gRPC status code and message.
     */
    public ArmeriaStatusException(int code, @Nullable String message) {
        this(code, message, null, null);
    }

    /**
     * Constructs an {@link ArmeriaStatusException} for the given gRPC status code, message
     * and grpcStatusDetailsBin. {@code grpcStatusDetailsBin} may be formatted as
     * {@code com.google.rpc.Status} to follow the
     * <a href="https://github.com/grpc/grpc/issues/24007">unofficial specification</a>.
     */
    public ArmeriaStatusException(int code, @Nullable String message, @Nullable byte[] grpcStatusDetailsBin) {
        this(code, message, grpcStatusDetailsBin, null);
    }

    /**
     * Constructs an {@link ArmeriaStatusException} for the given gRPC status code, message and cause.
     */
    public ArmeriaStatusException(int code, @Nullable String message, @Nullable Throwable cause) {
        this(code, message, null, cause);
    }

    /**
     * Constructs an {@link ArmeriaStatusException} for the given gRPC status code, message,
     * grpcStatusDetailsBin and cause. {@code grpcStatusDetailsBin} may be formatted as
     * {@code com.google.rpc.Status} to follow the
     * <a href="https://github.com/grpc/grpc/issues/24007">unofficial specification</a>.
     */
    public ArmeriaStatusException(int code, @Nullable String message, @Nullable byte[] grpcStatusDetailsBin,
                                  @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
        this.grpcStatusDetailsBin = grpcStatusDetailsBin;
    }

    /**
     * Returns the gRPC status code for this {@link ArmeriaStatusException}.
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the gRPC details binary for this {@link ArmeriaStatusException}.
     */
    @Nullable
    public byte[] getGrpcStatusDetailsBin() {
        return grpcStatusDetailsBin;
    }
}
