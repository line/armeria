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

import static com.linecorp.armeria.server.grpc.UnframedGrpcErrorHandlerUtil.responseCause;
import static com.linecorp.armeria.server.grpc.UnframedGrpcErrorHandlerUtil.withDefault;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Error handler which maps a gRPC response to an {@link HttpResponse} in plaintext format.
 */
final class TextUnframedGrpcErrorHandler implements UnframedGrpcErrorHandler {

    private static final UnframedGrpcStatusMappingFunction DEFAULT_STATUS_MAPPING_FUNCTION =
            UnframedGrpcStatusMappingFunction.of();

    private static final TextUnframedGrpcErrorHandler DEFAULT =
            new TextUnframedGrpcErrorHandler(DEFAULT_STATUS_MAPPING_FUNCTION);

    static TextUnframedGrpcErrorHandler of() {
        return DEFAULT;
    }

    static TextUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        if (DEFAULT_STATUS_MAPPING_FUNCTION == statusMappingFunction) {
            return DEFAULT;
        }
        return new TextUnframedGrpcErrorHandler(statusMappingFunction);
    }

    private final UnframedGrpcStatusMappingFunction statusMappingFunction;

    private TextUnframedGrpcErrorHandler(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        this.statusMappingFunction = withDefault(statusMappingFunction);
    }

    /**
     * Returns a plaintext response.
     *
     * @param ctx the service context.
     * @param status the gRPC {@link Status} code.
     * @param response the gRPC response.
     *
     * @return the {@link HttpResponse}.
     */
    @Override
    public HttpResponse handle(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response) {
        final Code grpcCode = status.getCode();
        final String grpcMessage = status.getDescription();
        final Throwable cause = responseCause(ctx);
        final HttpStatus httpStatus = statusMappingFunction.apply(ctx, status, cause);
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                               .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                                               .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                       grpcCode.value())
                                                               .build();
        final HttpData content;
        try (TemporaryThreadLocals ttl = TemporaryThreadLocals.acquire()) {
            final StringBuilder msg = ttl.stringBuilder();
            msg.append("grpc-code: ").append(grpcCode.name());
            if (grpcMessage != null) {
                msg.append(", ").append(grpcMessage);
            }
            if (cause != null && ctx.config().verboseResponses()) {
                msg.append("\nstack-trace:\n").append(Exceptions.traceText(cause));
            }
            content = HttpData.ofUtf8(msg);
        }
        return HttpResponse.of(responseHeaders, content);
    }
}
