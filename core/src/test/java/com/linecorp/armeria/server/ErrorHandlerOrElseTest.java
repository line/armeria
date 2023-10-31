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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ErrorHandlerOrElseTest {

    private static final Queue<String> handlerEvents = new ConcurrentLinkedQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                throw new IllegalStateException();
            });
            sb.errorHandler((ctx, cause) -> {
                handlerEvents.add("Server1");
                return null;
            });
            sb.errorHandler((ctx, cause) -> {
                handlerEvents.add("Server2");
                return null;
            });
            sb.virtualHost("foo.com")
              .service("/", (ctx, req) -> {
                  throw new IllegalStateException();
              })
              .errorHandler((ctx, cause) -> {
                  handlerEvents.add("VirtualHost1");
                  return null;
              })
              .errorHandler((ctx, cause) -> {
                  handlerEvents.add("VirtualHost2");
                  return null;
              })
              .route()
              .path("/route")
              .errorHandler((ctx, cause) -> {
                  handlerEvents.add("Service1");
                  return null;
              })
              .errorHandler((ctx, cause) -> {
                  handlerEvents.add("Service2");
                  return null;
              })
              .build((ctx, req) -> {
                  throw new IllegalStateException();
              });
        }
    };

    @Test
    void shouldChainErrorHandlerWithOrElse() {
        final BlockingWebClient client = server.blockingWebClient();
        assertThat(client.get("/").status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handlerEvents).containsExactly("Server1", "Server2");
        handlerEvents.clear();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {
            final BlockingWebClient fooClient = WebClient.builder("http://foo.com:" + server.httpPort())
                                                         .factory(factory)
                                                         .build()
                                                         .blocking();
            assertThat(fooClient.get("/").status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(handlerEvents).containsExactly(
                    "VirtualHost1", "VirtualHost2",
                    "Server1", "Server2");

            handlerEvents.clear();
            assertThat(fooClient.get("/route").status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(handlerEvents).containsExactly(
                    "Service1", "Service2",
                    "VirtualHost1", "VirtualHost2",
                    "Server1", "Server2");
            handlerEvents.clear();
        }
    }
}
