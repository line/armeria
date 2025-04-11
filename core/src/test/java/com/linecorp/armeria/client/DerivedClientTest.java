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
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DerivedClientTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // The two different ports are used to create 2 endpoints.
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
        final WebClient webClient = server.webClient(cb -> cb.options(successFunction));

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
        final BlockingWebClient client = WebClient.builder(HTTP, endpointGroup)
                                                  .factory(ClientFactory.insecure())
                                                  .build()
                                                  .blocking();
        assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        assertThat(server.requestContextCaptor().take().request().headers().contains("foo")).isFalse();

        BlockingWebClient derivedWithAdditionalOptions =
                Clients.newDerivedClient(client, ClientOptions.HEADERS.newValue(HttpHeaders.of("foo", "1")));
        assertThat(derivedWithAdditionalOptions.endpointGroup())
                .isSameAs(endpointGroup);
        assertThat(derivedWithAdditionalOptions.get("/").status()).isEqualTo(HttpStatus.OK);
        assertThat(server.requestContextCaptor().take().request().headers().contains("foo", "1"))
                .isTrue();

        derivedWithAdditionalOptions =
                Clients.newDerivedClient(client, ImmutableList.of(
                        ClientOptions.HEADERS.newValue(HttpHeaders.of("foo", "2"))));
        assertThat(derivedWithAdditionalOptions.endpointGroup())
                .isSameAs(endpointGroup);
        assertThat(derivedWithAdditionalOptions.get("/").status()).isEqualTo(HttpStatus.OK);
        assertThat(server.requestContextCaptor().take().request().headers().contains("foo", "2")).isTrue();

        derivedWithAdditionalOptions =
                Clients.newDerivedClient(client, options -> {
                    return options.toBuilder()
                                  .options(options)
                                  .setHeader("foo", "3")
                                  .build();
                });
        assertThat(derivedWithAdditionalOptions.endpointGroup())
                .isSameAs(endpointGroup);
        assertThat(derivedWithAdditionalOptions.get("/").status()).isEqualTo(HttpStatus.OK);
        assertThat(server.requestContextCaptor().take().request().headers().contains("foo", "3")).isTrue();
    }

    private static Stream<Arguments> derivationDoesNotAddPreprocessors_args() {
        final Endpoint endpoint = Endpoint.of("127.0.0.1");
        return Stream.of(
                Arguments.of(WebClient.of()),
                Arguments.of(WebClient.of((delegate, ctx, req) -> HttpResponse.of(200))),
                Arguments.of(WebClient.builder((delegate, ctx, req) -> HttpResponse.of(200), "/prefix")
                                      .build()),
                Arguments.of(WebClient.builder(HttpPreprocessor.of(HTTP, endpoint), "/prefix")
                                      .preprocessor(HttpPreprocessor.of(HTTPS, endpoint))
                                      .build()),
                Arguments.of(Clients.builder(ClientPreprocessors.builder()
                                                                .add(HttpPreprocessor.of(HTTP, endpoint))
                                                                .addRpc(RpcPreprocessor.of(HTTP, endpoint))
                                                                .build())
                                    .preprocessor((delegate, ctx, req) -> HttpResponse.of(201))
                                    .build(WebClient.class))
        );
    }

    @ParameterizedTest
    @MethodSource("derivationDoesNotAddPreprocessors_args")
    void derivationDoesNotAddPreprocessors(WebClient webClient) {
        final ClientPreprocessors origPreprocessors = webClient.options().clientPreprocessors();
        final List<HttpPreprocessor> httpPreprocessors = origPreprocessors.preprocessors();
        final List<RpcPreprocessor> rpcPreprocessors = origPreprocessors.rpcPreprocessors();

        final WebClient derivedClient =
                Clients.newDerivedClient(webClient,
                                         ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(1L));
        final ClientPreprocessors newPreprocessors = derivedClient.options().clientPreprocessors();
        assertThat(httpPreprocessors).containsExactlyElementsOf(newPreprocessors.preprocessors());
        assertThat(rpcPreprocessors).containsExactlyElementsOf(newPreprocessors.rpcPreprocessors());
    }
}
