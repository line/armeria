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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DerivedClientTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.http(0);
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of("OK");
            });
        }
    };

    @Test
    void shouldDeriveWebClients() {
        final ClientOptionValue<SuccessFunction> successFunction =
                ClientOptions.SUCCESS_FUNCTION.doNewValue((ctx, log) -> true);
        final WebClient webClient = WebClient.builder(server.httpUri())
                                             .options(successFunction)
                                             .build();

        final ClientOptionValue<HttpHeaders> blockingClientOption =
                ClientOptions.HEADERS.newValue(HttpHeaders.of("foo", "bar"));
        final BlockingWebClient blockingClient =
                Clients.newDerivedClient(webClient.blocking(), blockingClientOption);
        final ClientOptions blockingClientOptions = blockingClient.options();
        assertThat(blockingClientOptions.get(ClientOptions.SUCCESS_FUNCTION))
                .isEqualTo(successFunction.value());
        assertThat(blockingClientOptions.get(blockingClientOption.option()))
                .isEqualTo(blockingClientOption.value());

        final ClientOptionValue<HttpHeaders> restClientOption =
                ClientOptions.HEADERS.newValue(HttpHeaders.of("foo", "baz"));
        final RestClient restClient =
                Clients.newDerivedClient(webClient.asRestClient(), restClientOption);

        final ClientOptions restClientOptions = restClient.options();
        assertThat(restClientOptions.get(ClientOptions.SUCCESS_FUNCTION))
                .isEqualTo(successFunction.value());
        assertThat(restClientOptions.get(restClientOption.option()))
                .isEqualTo(restClientOption.value());
    }

    @Test
    void shouldCopyEndpoint() throws InterruptedException {
        final List<Endpoint> endpoints =
                server.server().activePorts().values().stream()
                      .map(serverPort -> {
                          return Endpoint.of("127.0.0.1", serverPort.localAddress().getPort());
                      }).collect(toImmutableList());
        final EndpointGroup endpointGroup = EndpointGroup.of(endpoints);
        final BlockingWebClient client = WebClient.builder(SessionProtocol.HTTP, endpointGroup)
                                                  .factory(ClientFactory.insecure())
                                                  .build()
                                                  .blocking();
        assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        server.requestContextCaptor().take();

        BlockingWebClient derivedWithAdditionalOptions =
                Clients.newDerivedClient(client, ClientOptions.HEADERS.newValue(HttpHeaders.of("foo", "bar")));
        assertThat(derivedWithAdditionalOptions.endpointGroup())
                .isEqualTo(endpointGroup);
        assertThat(derivedWithAdditionalOptions.get("/").status()).isEqualTo(HttpStatus.OK);
        server.requestContextCaptor().take().request().headers().contains("foo", "bar");

        derivedWithAdditionalOptions =
                Clients.newDerivedClient(client, ImmutableList.of(
                        ClientOptions.HEADERS.newValue(HttpHeaders.of("foo", "bar"))));
        assertThat(derivedWithAdditionalOptions.endpointGroup())
                .isEqualTo(endpointGroup);
        assertThat(derivedWithAdditionalOptions.get("/").status()).isEqualTo(HttpStatus.OK);
        server.requestContextCaptor().take().request().headers().contains("foo", "bar");

        derivedWithAdditionalOptions =
                Clients.newDerivedClient(client, options -> {
                    return options.toBuilder()
                                  .options(options)
                                  .setHeader("foo", "bar")
                                  .build();
                });
        assertThat(derivedWithAdditionalOptions.endpointGroup())
                .isEqualTo(endpointGroup);
        assertThat(derivedWithAdditionalOptions.get("/").status()).isEqualTo(HttpStatus.OK);
        server.requestContextCaptor().take().request().headers().contains("foo", "bar");
    }
}
