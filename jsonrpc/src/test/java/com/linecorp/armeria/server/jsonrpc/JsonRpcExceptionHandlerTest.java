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
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonRpcExceptionHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final JsonRpcService jsonRpcService =
                    JsonRpcService.builder()
                                  .exceptionHandler(new FooExceptionHandler())
                                  .exceptionHandler(new BarExceptionHandler())
                                  .handler(new FailingRpcHandler())
                                  .build();
            sb.service("/json-rpc", jsonRpcService);
        }
    };

    @Test
    void customExceptionHandler() {
        final BlockingWebClient client = server.blockingWebClient();
        final JsonRpcRequest fooRequest = JsonRpcRequest.of(1, "foo");
        final ResponseEntity<JsonRpcResponse> fooResponse = execute(client, fooRequest);
        assertThat(fooResponse.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final JsonRpcResponse fooRpcResponse = fooResponse.content();
        assertThat(fooRpcResponse.id()).isEqualTo(1);
        assertThat(fooRpcResponse.isSuccess()).isFalse();
        assertThat(fooRpcResponse.error().code()).isEqualTo(-32000);

        final JsonRpcRequest barRequest = JsonRpcRequest.of(2, "bar");
        final ResponseEntity<JsonRpcResponse> barResponse = execute(client, barRequest);
        assertThat(barResponse.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        final JsonRpcResponse barRpcResponse = barResponse.content();
        assertThat(barRpcResponse.id()).isEqualTo(2);
        assertThat(barRpcResponse.isSuccess()).isFalse();
        assertThat(barRpcResponse.error().code()).isEqualTo(JsonRpcError.INVALID_REQUEST.code());

        final JsonRpcRequest bazRequest = JsonRpcRequest.of(3, "baz");
        final ResponseEntity<JsonRpcResponse> bazResponse = execute(client, bazRequest);
        assertThat(bazResponse.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final JsonRpcResponse bazRpcResponse = bazResponse.content();
        assertThat(bazRpcResponse.id()).isEqualTo(3);
        assertThat(bazRpcResponse.isSuccess()).isFalse();
        assertThat(bazRpcResponse.error().code()).isEqualTo(JsonRpcError.INTERNAL_ERROR.code());
    }

    private ResponseEntity<JsonRpcResponse> execute(BlockingWebClient client, JsonRpcMessage request) {
        return client.prepare()
                     .post("/json-rpc")
                     .contentJson(request)
                     .asJson(JsonRpcResponse.class, status -> true)
                     .execute();
    }

    private static final class FailingRpcHandler implements JsonRpcHandler {

        @Override
        public CompletableFuture<@Nullable JsonRpcResponse> handleRpcCall(ServiceRequestContext ctx,
                                                                          JsonRpcMessage message) {
            final JsonRpcRequest req = (JsonRpcRequest) message;
            if ("foo".equals(req.method())) {
                throw new FooException();
            }
            if ("bar".equals(req.method())) {
                throw new BarException();
            }
            throw new AnticipatedException();
        }
    }

    private static final class FooException extends RuntimeException {

        private static final long serialVersionUID = -1660222993376401344L;
    }

    private static final class BarException extends RuntimeException {

        private static final long serialVersionUID = -1660222993376401344L;
    }

    private static final class FooExceptionHandler implements JsonRpcExceptionHandler {

        @Override
        public JsonRpcResponse handleException(ServiceRequestContext ctx, @Nullable JsonRpcMessage input,
                                               Throwable cause) {
            if (cause instanceof FooException) {
                return JsonRpcResponse.ofFailure(new JsonRpcError(-32000, "Foo error"));
            }
            return null;
        }
    }

    private static final class BarExceptionHandler implements JsonRpcExceptionHandler {

        @Override
        public JsonRpcResponse handleException(ServiceRequestContext ctx, @Nullable JsonRpcMessage input,
                                               Throwable cause) {
            if (cause instanceof BarException) {
                return JsonRpcResponse.ofFailure(JsonRpcError.INVALID_REQUEST);
            }
            return null;
        }
    }
}
