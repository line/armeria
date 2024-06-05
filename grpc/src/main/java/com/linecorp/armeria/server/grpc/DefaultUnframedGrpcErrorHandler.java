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

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;

final class DefaultUnframedGrpcErrorHandler implements UnframedGrpcErrorHandler {

    private static final JsonUnframedGrpcErrorHandler DEFAULT_JSON_UNFRAMED_GRPC_ERROR_HANDLER =
            JsonUnframedGrpcErrorHandler.of();

    private static final TextUnframedGrpcErrorHandler DEFAULT_TEXT_UNFRAMED_GRPC_ERROR_HANDLER =
            TextUnframedGrpcErrorHandler.of();

    private static final DefaultUnframedGrpcErrorHandler DEFAULT =
        new DefaultUnframedGrpcErrorHandler(
                DEFAULT_JSON_UNFRAMED_GRPC_ERROR_HANDLER, DEFAULT_TEXT_UNFRAMED_GRPC_ERROR_HANDLER);

    static DefaultUnframedGrpcErrorHandler of() {
        return DEFAULT;
    }

    static DefaultUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction mappingFunction) {
        return of(mappingFunction, null);
    }

    static DefaultUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction mappingFunction,
                                              @Nullable MessageMarshaller jsonMarshaller) {
        final JsonUnframedGrpcErrorHandler jsonHandler =
                jsonMarshaller == null ? JsonUnframedGrpcErrorHandler.of(mappingFunction)
                                       : JsonUnframedGrpcErrorHandler.of(mappingFunction, jsonMarshaller);
        final TextUnframedGrpcErrorHandler textHandler = TextUnframedGrpcErrorHandler.of(mappingFunction);

        if (jsonHandler == DEFAULT_JSON_UNFRAMED_GRPC_ERROR_HANDLER &&
            textHandler == DEFAULT_TEXT_UNFRAMED_GRPC_ERROR_HANDLER) {
            return DEFAULT;
        }

        return new DefaultUnframedGrpcErrorHandler(jsonHandler, textHandler);
    }

    private final JsonUnframedGrpcErrorHandler jsonUnframedGrpcErrorHandler;
    private final TextUnframedGrpcErrorHandler textUnframedGrpcErrorHandler;

    DefaultUnframedGrpcErrorHandler(JsonUnframedGrpcErrorHandler jsonUnframedGrpcErrorHandler,
                                    TextUnframedGrpcErrorHandler textUnframedGrpcErrorHandler) {
        this.jsonUnframedGrpcErrorHandler = jsonUnframedGrpcErrorHandler;
        this.textUnframedGrpcErrorHandler = textUnframedGrpcErrorHandler;
    }

    /**
     * Returns an HTTP response based on its content type.
     * If the content type of the response is JSON, this method delegates the handling to a JSON-specific
     * unframed gRPC error handler. Otherwise, it delegates to a text-based unframed gRPC error handler.
     *
     * @param ctx the service context.
     * @param status the gRPC {@link Status} code.
     * @param response the gRPC response.
     *
     * @return the {@link HttpResponse}.
     */
    @Override
    public HttpResponse handle(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response) {
        final MediaType grpcMediaType = response.contentType();
        if (grpcMediaType != null && grpcMediaType.isJson()) {
            return jsonUnframedGrpcErrorHandler.handle(ctx, status, response);
        } else {
            return textUnframedGrpcErrorHandler.handle(ctx, status, response);
        }
    }
}
