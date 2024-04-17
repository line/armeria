/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Status;

/**
 * Error handler which maps a gRPC response to an {@link HttpResponse} in plaintext format.
 */
@FunctionalInterface
@UnstableApi
public interface TextUnframedGrpcErrorHandler extends UnframedGrpcErrorHandler {
    /**
     * Returns a plaintext response.
     */
    static TextUnframedGrpcErrorHandler of() {
        return UnframedGrpcErrorHandlers.ofPlaintext(UnframedGrpcStatusMappingFunction.of());
    }

    /**
     * Returns a plaintext response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static TextUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return UnframedGrpcErrorHandlers.ofPlaintext(statusMappingFunction);
    }
}
