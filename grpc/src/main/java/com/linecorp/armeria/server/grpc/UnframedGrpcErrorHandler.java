/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;

/**
 * Error handler which maps a gRPC response to an {@link HttpResponse}.
 */
@FunctionalInterface
@UnstableApi
public interface UnframedGrpcErrorHandler {

    /**
     * Returns a new {@link UnframedGrpcErrorHandlerBuilder}.
     */
    @UnstableApi
    static UnframedGrpcErrorHandlerBuilder builder() {
        return new UnframedGrpcErrorHandlerBuilder();
    }

    /**
     * Returns a plain text or json response based on the content type.
     */
    static UnframedGrpcErrorHandler of() {
        return DefaultUnframedGrpcErrorHandler.of();
    }

    /**
     * Returns a plain text or json response based on the content type.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return DefaultUnframedGrpcErrorHandler.of(statusMappingFunction);
    }

    /**
     * Returns a JSON response based on Google APIs.
     * See <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     * for more information.
     */
    static UnframedGrpcErrorHandler ofJson() {
        return JsonUnframedGrpcErrorHandler.of();
    }

    /**
     * Returns a JSON response based on Google APIs.
     * See <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     * for more information.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofJson(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return JsonUnframedGrpcErrorHandler.of(statusMappingFunction);
    }

    /**
     * Returns a plain text response.
     */
    static UnframedGrpcErrorHandler ofPlainText() {
        return TextUnframedGrpcErrorHandler.of();
    }

    /**
     * Returns a plain text response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofPlainText(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return TextUnframedGrpcErrorHandler.of(statusMappingFunction);
    }

    /**
     * Maps the gRPC error response to the {@link HttpResponse}.
     *
     * @param ctx the service context.
     * @param status the gRPC {@link Status} code.
     * @param response the gRPC response.
     *
     * @return the {@link HttpResponse}.
     */
    HttpResponse handle(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response);
}
