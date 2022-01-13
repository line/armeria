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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpEntity;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TransformingResponsePreparationTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/string", (ctx, req) -> HttpResponse.of("hello"));
            sb.service("/500", (ctx, req) -> {
                throw HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });
            sb.service("/json", (ctx, req) -> HttpResponse.ofJson(new MyMessage("hello")));
            sb.service("/json_list",
                       (ctx, req) -> HttpResponse.ofJson(ImmutableList.of(new MyMessage("hello"))));
        }
    };

    @Test
    void customResponseAs() {
        final WebClient client = WebClient.of(server.httpUri());
        final String response = client.prepare()
                                      .get("/string")
                                      .as(res -> res.aggregate().thenApply(AggregatedHttpObject::contentUtf8))
                                      .execute()
                                      .join();
        assertThat(response).isEqualTo("hello");
    }

    @Test
    void futureResponseAs_bytes() {
        final WebClient client = WebClient.of(server.httpUri());
        final ResponseEntity<byte[]> response = client.prepare()
                                                      .get("/string")
                                                      .asBytes()
                                                      .execute()
                                                      .join();
        assertThat(response.content()).isEqualTo("hello".getBytes());
    }

    @Test
    void futureResponseAs_string() {
        final WebClient client = WebClient.of(server.httpUri());
        final ResponseEntity<String> response = client.prepare()
                                                      .get("/string")
                                                      .asString()
                                                      .execute()
                                                      .join();
        assertThat(response.content()).isEqualTo("hello");
    }

    @Test
    void futureResponseAs_json() {
        final WebClient client = WebClient.of(server.httpUri());
        final ResponseEntity<MyMessage> response = client.prepare()
                                                         .get("/json")
                                                         .asJson(MyMessage.class)
                                                         .execute()
                                                         .join();
        assertThat(response.content()).isEqualTo(new MyMessage("hello"));
    }

    @Test
    void futureResponseAs_json_invalidResponseStatus() {
        final WebClient client = WebClient.of(server.httpUri());
        final CompletableFuture<ResponseEntity<MyMessage>> future =
                client.prepare()
                      .get("/500")
                      .asJson(MyMessage.class)
                      .execute();
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InvalidHttpResponseException.class)
                .satisfies(cause -> {
                    assertThat(((InvalidHttpResponseException) cause.getCause()).response().status())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    @Test
    void futureResponseAs_jsonList() {
        final WebClient client = WebClient.of(server.httpUri());
        final ResponseEntity<List<MyMessage>> response = client.prepare()
                                                               .get("/json_list")
                                                               .asJson(new TypeReference<List<MyMessage>>() {})
                                                               .execute()
                                                               .join();
        assertThat(response.content()).containsExactly(new MyMessage("hello"));
    }

    @Test
    void futureResponseAs_chainedAs() {
        final WebClient client = WebClient.of(server.httpUri());
        final MyMessage response =
                client.prepare()
                      .get("/string")
                      .asString()
                      .as(entity -> ResponseEntity.of(entity.headers(), new MyMessage(entity.content())))
                      .as(HttpEntity::content)
                      .execute()
                      .join();
        assertThat(response).isEqualTo(new MyMessage("hello"));
    }

    @Test
    void futureResponseAs_recover() {
        final WebClient client = WebClient.of(server.httpUri());
        final ResponseEntity<MyResponse> response =
                client.prepare()
                      .get("/json_list")
                      .<MyResponse>asJson(MyMessage.class)
                      .recover(cause -> {
                          if (cause instanceof InvalidHttpResponseException) {
                              final AggregatedHttpResponse res =
                                      ((InvalidHttpResponseException) cause).response();
                              return ResponseEntity.of(res.headers(),
                                                       new MyError(res.status().codeAsText(),
                                                                   res.contentUtf8()));
                          }
                          return null;
                      })
                      .execute()
                      .join();
        assertThat(response.content()).isInstanceOf(MyError.class);
        final MyError myError = (MyError) response.content();
        assertThat(myError.code).isEqualTo(HttpStatus.OK.codeAsText());
    }

    @Test
    void futureResponseAs_mapError() {
        final WebClient client = WebClient.of(server.httpUri());
        final CompletableFuture<ResponseEntity<MyResponse>> future =
                client.prepare()
                      .get("/json_list")
                      .<MyResponse>asJson(MyMessage.class)
                      .mapError(cause -> {
                          if (cause instanceof InvalidHttpResponseException) {
                              return cause.getCause();
                          }
                          return null;
                      })
                      .execute();
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);

        final CompletableFuture<ResponseEntity<MyResponse>> future2 =
                client.prepare()
                      .get("/json_list")
                      .<MyResponse>asJson(MyMessage.class)
                      .mapError(cause -> {
                          if (cause instanceof InvalidHttpResponseException) {
                              return cause.getCause();
                          }
                          return null;
                      }).mapError(cause -> {
                          // Not handled.
                          return null;
                      })
                      .execute();
        assertThatThrownBy(future2::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    interface MyResponse {}

    static final class MyMessage implements MyResponse {
        @JsonProperty("message")
        private String message;

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

            final MyMessage myMessage = (MyMessage) o;
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
}
