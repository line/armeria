/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ConditionalResponseAsTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws JsonProcessingException {
            sb.service("/string", (ctx, req) -> HttpResponse.of("hello"));
            sb.service("/server_error", (ctx, req) ->
                    HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, new MyMessage("ServerError"))
            );
        }
    };

    interface MyResponse {}

    static final class EmptyMessage implements MyResponse {}

    static final class MyMessage implements MyResponse {
        @JsonProperty("message")
        private final String message;

        @JsonCreator
        MyMessage(@JsonProperty("message") String message) {
            this.message = message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MyMessage)) {
                return false;
            }

            final MyMessage
                    myMessage = (MyMessage) o;
            return message.equals(myMessage.message);
        }

        @Override
        public int hashCode() {
            return message.hashCode();
        }
    }

    static final class MyError implements MyResponse {
        private final String code;
        private final String body;

        private MyError(String code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    @Test
    void json_andThen() {
        final String response =
                WebClient.of(server.httpUri()).prepare().get("/server_error")
                         .as(ResponseAs.blocking()
                                       .andThen(res -> "Unexpected server error",
                                                res -> res.status().isServerError())
                                       .andThen(res -> "missing header",
                                                res -> !res.headers().contains("x-header"))
                                       .orElse(AggregatedHttpObject::contentUtf8)).execute();
        assertThat(response).isEqualTo("Unexpected server error");

        final MyResponse response2 =
                WebClient.of(server.httpUri()).prepare().get("/server_error")
                         .as(ResponseAs.blocking()
                                       .<MyResponse>andThen(
                                               res -> new MyError(res.status().codeAsText(), res.contentUtf8()),
                                               res -> res.status().isError()
                                       )
                                       .andThen(
                                               res -> new EmptyMessage(),
                                               res -> !res.headers().contains("x-header"))
                                       .orElse(res -> new MyMessage(res.contentUtf8()))).execute();
        assertThat(response2).isInstanceOf(MyError.class);
    }

    @Test
    void json_orElse() {
        final String response =
                WebClient.of(server.httpUri()).prepare().get("/string")
                         .as(ResponseAs.blocking()
                                       .andThen(res -> "Unexpected server error",
                                                res -> res.status().isServerError())
                                       .andThen(res -> "missing header",
                                                res -> res.headers().contains("x-header"))
                                       .orElse(AggregatedHttpObject::contentUtf8)).execute();
        assertThat(response).isEqualTo("hello");
    }
}
