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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContextPushHookTest {

    private static final Queue<String> hookEvents = new ConcurrentLinkedQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.contextHook(() -> {
                  if (hookEvents.contains("ServerBuilder/push")) {
                      return () -> {};
                  }
                  hookEvents.add("ServerBuilder/push");
                  return () -> {};
              })
              .route()
              .contextHook(() -> {
                  if (hookEvents.contains("Service/push")) {
                      return () -> {};
                  }
                  hookEvents.add("Service/push");
                  return () -> {};
              })
              .get("/server")
              .build((ctx, req) -> HttpResponse.of(200));

            sb.virtualHost("foo.com")
              .contextHook(() -> {
                  if (hookEvents.contains("VirtualHost/push")) {
                      return () -> {};
                  }
                  hookEvents.add("VirtualHost/push");
                  return () -> {};
              })
              .route()
              .contextHook(() -> {
                  if (hookEvents.contains("VirtualService/push")) {
                      return () -> {};
                  }
                  hookEvents.add("VirtualService/push");
                  return () -> {};
              })
              .get("/virtualhost")
              .build((ctx, req) -> HttpResponse.of(200));

            sb.decorator((delegate, ctx, req) -> {
                ctx.hook(() -> {
                    if (hookEvents.contains("ServiceContext/push")) {
                        return () -> {};
                    }
                    hookEvents.add("ServiceContext/push");
                    return () -> {};
                });
                // Push to trigger the hook added by this decorator.
                try (SafeCloseable ignored = ctx.push()) {
                    return delegate.serve(ctx, req);
                }
            });
        }
    };

    @Test
    void shouldRunHooksWhenContextIsPushed() {
        try (ClientFactory factory = ClientFactory.builder()
                                                   .addressResolverGroupFactory(
                                                           unused -> MockAddressResolverGroup.localhost())
                                                   .build()) {
            final BlockingWebClient client =
                    WebClient.builder()
                             .contextHook(() -> {
                                 if (hookEvents.contains("ClientBuilder/push")) {
                                     return () -> {};
                                 }
                                 hookEvents.add("ClientBuilder/push");
                                 return () -> {};
                             })
                             .decorator((delegate, ctx, req) -> {
                                 ctx.hook(() -> {
                                     if (hookEvents.contains("ClientContext/push")) {
                                         return () -> {};
                                     }
                                     hookEvents.add("ClientContext/push");
                                     return () -> {};
                                 });
                                 // Push to trigger the hook added by this decorator.
                                 try (SafeCloseable ignored = ctx.push()) {
                                     return delegate.execute(ctx, req);
                                 }
                             })
                             .factory(factory)
                             .build()
                             .blocking();
            AggregatedHttpResponse response = client.get(server.httpUri().resolve("/server").toString());
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(hookEvents).containsExactly(
                    "ClientBuilder/push",
                    "ClientContext/push",
                    "ServerBuilder/push",
                    "Service/push",
                    "ServiceContext/push");
            hookEvents.clear();
            response = client.get("http://foo.com:" + server.httpPort() + "/virtualhost");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(hookEvents).containsExactly(
                    "ClientBuilder/push",
                    "ClientContext/push",
                    "ServerBuilder/push",
                    "VirtualHost/push",
                    "VirtualService/push",
                    "ServiceContext/push");
        }
    }
}
