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
 * Error handler which maps a gRPC response to an {@link HttpResponse} in json format.
 */
@FunctionalInterface
@UnstableApi
public interface JsonUnframedGrpcErrorHandler extends UnframedGrpcErrorHandler {

    /**
     * Returns a JSON response based on Google APIs.
     * See <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     * for more information.
     */
    static JsonUnframedGrpcErrorHandler of() {
        return UnframedGrpcErrorHandlers.ofJson(UnframedGrpcStatusMappingFunction.of());
    }

    /**
     * Returns a JSON response based on Google APIs.
     * See <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     * for more information.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static JsonUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return UnframedGrpcErrorHandlers.ofJson(statusMappingFunction);
    }
}
