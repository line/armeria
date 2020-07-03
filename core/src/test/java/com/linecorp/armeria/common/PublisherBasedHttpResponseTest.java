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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class PublisherBasedHttpResponseTest {

    private static final AtomicBoolean exceptionIsRaised = new AtomicBoolean();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                final Flux<HttpObject> publisher = Flux.just(ResponseHeaders.of(200), HttpData.ofUtf8("hello"));
                final HttpResponse response = HttpResponse.of(publisher);
                response.whenComplete().whenComplete((unused, cause) -> {
                    exceptionIsRaised.set(cause != null);
                });
                return response;
            });
        }
    };

    @Test
    void shouldCompleteWithNoException() {
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        assertThat(exceptionIsRaised.get()).isFalse();
    }
}
