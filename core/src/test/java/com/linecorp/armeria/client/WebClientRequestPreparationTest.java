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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.TransformingResponsePreparationTest.MyMessage;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebClientRequestPreparationTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/json", (ctx, req) -> HttpResponse.ofJson(new MyMessage("hello")));
        }
    };

    private static WebClient client;

    @BeforeAll
    static void beforeAll() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void prepare_asJson() {
        final CompletableFuture<ResponseEntity<MyMessage>> future1 = client.prepare()
                .get("/json")
                .asJson(MyMessage.class)
                .execute();

        final ResponseEntity<MyMessage> response1 = future1.join();

        assertThat(response1.content()).isEqualTo(new MyMessage("hello"));
        assertThat(response1.headers().status()).isEqualTo(HttpStatus.OK);

        final ObjectMapper mapper = new ObjectMapper();
        final CompletableFuture<ResponseEntity<MyMessage>> future2 = client.prepare()
                .get("/json")
                .asJson(MyMessage.class, mapper)
                .execute();

        final ResponseEntity<MyMessage> response2 = future2.join();

        assertThat(response2.content()).isEqualTo(new MyMessage("hello"));
        assertThat(response2.headers().status()).isEqualTo(HttpStatus.OK);
    }
}
