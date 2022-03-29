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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.TransformingResponsePreparationTest.MyMessage;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BlockingWebClientTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/string", (ctx, req) -> HttpResponse.of("OK"));
            sb.service("/500", (ctx, req) -> {
                throw HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });
            sb.service("/json", (ctx, req) -> HttpResponse.ofJson(new MyMessage("hello")));
            sb.service("/json_list",
                       (ctx, req) -> HttpResponse.ofJson(ImmutableList.of(new MyMessage("hello"))));
        }
    };

    private static BlockingWebClient client;

    @BeforeAll
    static void beforeAll() {
        client = WebClient.of(server.httpUri()).blocking();
    }

    @Test
    void simple() {
        final AggregatedHttpResponse response = client.get("/string");
        assertThat(response.contentUtf8()).isEqualTo("OK");
    }

    @Test
    void prepare_asBytes() {
        final ResponseEntity<byte[]> response = client.prepare()
                                                      .get("/string")
                                                      .asBytes()
                                                      .execute();
        assertThat(response.content()).isEqualTo("OK".getBytes());
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void prepare_asString() {
        final ResponseEntity<String> response = client.prepare()
                                                      .get("/string")
                                                      .asString()
                                                      .execute();
        assertThat(response.content()).isEqualTo("OK");
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void prepare_asJson() {
        final ResponseEntity<MyMessage> response = client.prepare()
                                                         .get("/json")
                                                         .asJson(MyMessage.class)
                                                         .execute();
        assertThat(response.content()).isEqualTo(new MyMessage("hello"));
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<List<MyMessage>> response2 = client.prepare()
                                                                .get("/json_list")
                                                                .asJson(new TypeReference<List<MyMessage>>() {})
                                                                .execute();
        assertThat(response2.content()).containsExactly(new MyMessage("hello"));
        assertThat(response2.headers().status()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> {
            client.prepare()
                  .get("/json_list")
                  .asJson(MyMessage.class)
                  .execute();
        }).isInstanceOf(InvalidHttpResponseException.class)
          .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void prepare_invalidResponseStatus() {
        final InvalidHttpResponseException throwable = (InvalidHttpResponseException) catchThrowable(() -> {
            client.prepare()
                  .get("/500")
                  .asJson(MyMessage.class)
                  .execute();
        });
        assertThat(throwable.response().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void apiConsistencyWithWebClient() {
        final List<Method> webClientMethods =
                Arrays.stream(WebClient.class.getDeclaredMethods())
                      .filter(method -> method.getReturnType().isAssignableFrom(HttpResponse.class))
                      .collect(toImmutableList());

        final List<Method> blockingClientMethods =
                Arrays.stream(BlockingWebClient.class.getDeclaredMethods())
                      .filter(method -> method.getReturnType().isAssignableFrom(AggregatedHttpResponse.class))
                      .collect(toImmutableList());

        for (Method method : webClientMethods) {
            final Optional<Method> found = blockingClientMethods.stream().filter(method0 -> {
                return method.getName().equals(method0.getName()) &&
                       Arrays.equals(method.getParameterTypes(), method0.getParameterTypes());
            }).findFirst();
            assertThat(found).isPresent();
        }
    }
}
