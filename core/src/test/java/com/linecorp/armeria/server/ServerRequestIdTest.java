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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class ServerRequestIdTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestIdGenerator(ctx -> RequestId.of(1L))   // for default
              .service("/default_virtual_host",
                       (ctx, req) -> HttpResponse.of(ctx.id().toString()))
              .virtualHost("foo.com")
              .requestIdGenerator(ctx -> RequestId.of(2L))  // for virtual host
              .service("/custom_virtual_host",
                       (ctx, req) -> HttpResponse.of(ctx.id().toString()))
              .route()
              .requestIdGenerator(ctx -> RequestId.of(3L)) // for service
              .get("/service_config")
              .build((ctx, req) -> HttpResponse.of(ctx.id().toString()));
        }
    };

    private static ClientFactory clientFactory;

    @BeforeAll
    static void init() {
        clientFactory = ClientFactory.builder()
                                     .addressResolverGroupFactory(group -> MockAddressResolverGroup.localhost())
                                     .build();
    }

    @AfterAll
    static void destroy() {
        clientFactory.closeAsync();
    }

    @Test
    void hierarchyRequestIdGeneratorConfiguration() {
        final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                  .factory(clientFactory)
                                                  .build()
                                                  .blocking();
        final BlockingWebClient fooClient = WebClient.builder("http://foo.com:" + server.httpPort())
                                                     .factory(clientFactory)
                                                     .build()
                                                     .blocking();

        // choose from default server config
        assertThat(client.get("/default_virtual_host").contentUtf8())
                .isEqualTo(RequestId.of(1L).text());

        // choose from 'foo.com' virtual host
        assertThat(fooClient.get("/default_virtual_host").contentUtf8())
                .isEqualTo(RequestId.of(2L).text());
        // choose from 'foo.com' virtual host
        assertThat(fooClient.get("/custom_virtual_host").contentUtf8())
                .isEqualTo(RequestId.of(2L).text());
        // choose from 'foo.com/service_config' service
        assertThat(fooClient.get("/service_config").contentUtf8())
                .isEqualTo(RequestId.of(3L).text());
    }
}
