/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import reactor.core.publisher.Flux;

class PublisherBasedHttpResponseTest {

    static AtomicReference<Throwable> exception;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                final Flux<HttpObject> publisher = Flux.just(ResponseHeaders.of(200), HttpData.ofUtf8("hello"));
                final HttpResponse response = HttpResponse.of(publisher);
                response.whenComplete().whenComplete((unused, cause) -> {
                    exception.set(cause);
                });
                return response;
            });
        }
    };

    @BeforeEach
    void setUp() {
        exception = new AtomicReference<>();
    }

    @Test
    void shouldCompleteWithNoException() {
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        assertThat(exception.get()).isNull();
    }
}
