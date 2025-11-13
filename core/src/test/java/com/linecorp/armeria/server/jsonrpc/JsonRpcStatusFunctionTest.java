/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonRpcStatusFunctionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // Service using the default JsonRpcStatusFunction
            final JsonRpcService defaultStatusService =
                    JsonRpcService.builder()
                                   .handler(new ErrorReturningHandler())
                                   .build();

            // Service using a custom status function that maps INVALID_PARAMS -> 429, else fallback
            final JsonRpcService customStatusService =
                    JsonRpcService.builder()
                                   .handler(new ErrorReturningHandler())
                                   .statusFunction(new InvalidParamsTo429StatusFunction())
                                   .build();

            sb.service("/json-rpc-default", defaultStatusService);
            sb.service("/json-rpc-custom", customStatusService);
        }
    };

    @Test
    void defaultStatusMapping() {
        final BlockingWebClient client = server.blockingWebClient();

        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(1, "invalidRequest")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(2, "methodNotFound")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(3, "invalidParams")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(4, "parseError")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(5, "internalError")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // Reserved implementation-defined server error range (-32099..-32000)
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(6, "reservedCustom")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // An arbitrary non-reserved application error also defaults to 500
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(7, "other")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // Success should return 200 OK
        assertThat(statusFor(client, "/json-rpc-default", JsonRpcRequest.of(8, "success")))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void customStatusMappingOverridesAndFallbacks() {
        final BlockingWebClient client = server.blockingWebClient();

        // Custom function overrides INVALID_PARAMS to 412
        assertThat(statusFor(client, "/json-rpc-custom", JsonRpcRequest.of(1, "invalidParams")))
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);

        // Others should fallback to default function
        assertThat(statusFor(client, "/json-rpc-custom", JsonRpcRequest.of(2, "invalidRequest")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusFor(client, "/json-rpc-custom", JsonRpcRequest.of(3, "internalError")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(statusFor(client, "/json-rpc-custom", JsonRpcRequest.of(4, "reservedCustom")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static HttpStatus statusFor(BlockingWebClient client, String path, JsonRpcMessage request) {
        final ResponseEntity<JsonRpcResponse> res = client.prepare()
                                                          .post(path)
                                                          .contentJson(request)
                                                          .asJson(JsonRpcResponse.class, status -> true)
                                                          .execute();
        return res.status();
    }

    private static final class ErrorReturningHandler implements JsonRpcHandler {

        @Override
        public CompletableFuture<@Nullable JsonRpcResponse> handleRpcCall(ServiceRequestContext ctx,
                                                                          JsonRpcMessage message) {
            if (!(message instanceof JsonRpcRequest)) {
                return UnmodifiableFuture.completedFuture(null);
            }
            final JsonRpcRequest req = (JsonRpcRequest) message;
            final String method = req.method();
            switch (method) {
                case "invalidRequest":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(JsonRpcError.INVALID_REQUEST));
                case "methodNotFound":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(JsonRpcError.METHOD_NOT_FOUND));
                case "invalidParams":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(JsonRpcError.INVALID_PARAMS));
                case "parseError":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(JsonRpcError.PARSE_ERROR));
                case "internalError":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(JsonRpcError.INTERNAL_ERROR));
                case "reservedCustom":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(new JsonRpcError(-32001, "Reserved custom")));
                case "other":
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(new JsonRpcError(123, "App error")));
                case "success":
                    return UnmodifiableFuture.completedFuture(JsonRpcResponse.ofSuccess("ok"));
                default:
                    return UnmodifiableFuture.completedFuture(
                            JsonRpcResponse.ofFailure(JsonRpcError.METHOD_NOT_FOUND));
            }
        }
    }

    private static final class InvalidParamsTo429StatusFunction implements JsonRpcStatusFunction {

        @Override
        public HttpStatus toHttpStatus(ServiceRequestContext ctx, JsonRpcRequest request,
                                       JsonRpcResponse response, JsonRpcError error) {
            if (error.code() == JsonRpcError.INVALID_PARAMS.code()) {
                return HttpStatus.PRECONDITION_FAILED;
            }
            return null; // Delegate to the default function
        }
    }
}

