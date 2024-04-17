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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.server.grpc.JsonUnframedGrpcErrorHandler.ERROR_DETAILS_MARSHALLER;
import static com.linecorp.armeria.server.grpc.UnframedGrpcErrorHandlerUtil.withDefault;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import io.grpc.Status;

final class UnframedGrpcErrorHandlers {
    /**
     * Returns a plaintext or JSON response based on the content type.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return of(statusMappingFunction, ERROR_DETAILS_MARSHALLER);
    }

    static UnframedGrpcErrorHandler of(
            UnframedGrpcStatusMappingFunction statusMappingFunction, MessageMarshaller jsonMarshaller) {
        final UnframedGrpcStatusMappingFunction mappingFunction = withDefault(statusMappingFunction);
        return (ctx, status, response) -> {
            final MediaType grpcMediaType = response.contentType();
            if (grpcMediaType != null && grpcMediaType.isJson()) {
                return new JsonUnframedGrpcErrorHandler(mappingFunction, jsonMarshaller)
                        .handle(ctx, status, response);
            } else {
                return new TextUnframedGrpcErrorHandler(mappingFunction).handle(ctx, status, response);
            }
        };
    }

    private UnframedGrpcErrorHandlers() {}
}
