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

package com.linecorp.armeria.client.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.internal.client.auth.oauth2.MockOAuth2ClientCredentialsService;
import com.linecorp.armeria.internal.server.auth.oauth2.MockOAuth2IntrospectionService;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class OAuth2ClientCredentialsGrantTest {

    static final String CLIENT_CREDENTIALS = "dGVzdF9jbGllbnQ6Y2xpZW50X3NlY3JldA=="; //test_client:client_secret
    static final String SERVER_CREDENTIALS = "dGVzdF9zZXJ2ZXI6c2VydmVyX3NlY3JldA=="; //test_server:server_secret

    static final MockOAuth2AccessToken TOKEN =
        MockOAuth2AccessToken.generate("test_client", null, Duration.ofHours(3L),
                                       ImmutableMap.of("extension_field", "twenty-seven"), "read", "write");

    static final HttpService SERVICE = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    };

    @RegisterExtension
    static ServerExtension authServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/token", new MockOAuth2ClientCredentialsService()
                    .withAuthorizedClient("test_client", "client_secret")
                    .withAuthorizedClient("test_server", "server_secret")
                    .withClientToken("test_client", TOKEN));
            sb.annotatedService("/introspect", new MockOAuth2IntrospectionService()
                    .withAuthorizedClient("test_client", "client_secret")
                    .withAuthorizedClient("test_server", "server_secret")
                    .withClientToken("test_client", TOKEN));
        }
    };

    private final ServerExtension resourceServer = new ServerExtension(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final WebClient introspectClient = WebClient.of(authServer.httpUri());
            sb.service("/resource-read-write/",
                       AuthService.builder().addOAuth2(OAuth2TokenIntrospectionAuthorizer.builder(
                               introspectClient,
                               "/introspect/token/")
                               .realm("protected resource read-write")
                               .accessTokenType("Bearer")
                               .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                               .permittedScope("read", "write")
                               .build()
                       ).build(SERVICE));
            sb.service("/resource-read/",
                       AuthService.builder().addOAuth2(OAuth2TokenIntrospectionAuthorizer.builder(
                               introspectClient,
                               "/introspect/token/")
                               .realm("protected resource read")
                               .accessTokenType("Bearer")
                               .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                               .permittedScope("read")
                               .build()
                       ).build(SERVICE));
            sb.service("/resource-read-write-update/",
                       AuthService.builder().addOAuth2(OAuth2TokenIntrospectionAuthorizer.builder(
                               introspectClient,
                               "/introspect/token/")
                               .realm("protected resource read-write-update")
                               .accessTokenType("Bearer")
                               .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                               .permittedScope("read", "write", "update")
                               .build()
                       ).build(SERVICE));
        }
    };

    @Test
    public void testOk() throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());
        final OAuth2ClientCredentialsGrant grant = OAuth2ClientCredentialsGrant
                .builder(authClient, "/token/client/")
                .clientBasicAuthorization(() -> CLIENT_CREDENTIALS).build();
        try (Server server = resourceServer.start()) {

            final WebClient client = WebClient.builder(resourceServer.httpUri())
                                              .decorator(OAuth2Client.newDecorator(grant))
                                              .build();

            final AggregatedHttpResponse response1 = client.get("/resource-read-write/").aggregate().join();
            assertThat(response1.status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse response2 = client.get("/resource-read/").aggregate().join();
            assertThat(response2.status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse response3 =
                    client.get("/resource-read-write-update/").aggregate().join();
            assertThat(response3.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    public void testWithAuthorization() throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());
        final OAuth2ClientCredentialsGrant grant = OAuth2ClientCredentialsGrant
                .builder(authClient, "/token/client/")
                .clientBasicAuthorization(() -> CLIENT_CREDENTIALS).build();
        try (Server server = resourceServer.start()) {

            final WebClient client = WebClient.of(resourceServer.httpUri());

            final HttpRequest request1 = HttpRequest.of(HttpMethod.GET, "/resource-read-write/");
            final CompletionStage<HttpRequest> authorizedRequest1 = grant.withAuthorization(request1);

            final HttpRequest request2 = HttpRequest.of(HttpMethod.GET, "/resource-read/");
            final CompletionStage<HttpRequest> authorizedRequest2 = grant.withAuthorization(request2);

            final HttpRequest request3 = HttpRequest.of(HttpMethod.GET, "/resource-read-write-update/");
            final CompletionStage<HttpRequest> authorizedRequest3 = grant.withAuthorization(request3);

            final AggregatedHttpResponse response1 =
                    authorizedRequest1.thenCompose(signed -> client.execute(signed).aggregate())
                                      .toCompletableFuture().join();
            assertThat(response1.status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse response2 =
                    authorizedRequest2.thenCompose(signed -> client.execute(signed).aggregate())
                                      .toCompletableFuture().join();
            assertThat(response2.status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse response3 =
                    authorizedRequest3.thenCompose(signed -> client.execute(signed).aggregate())
                                      .toCompletableFuture().join();
            assertThat(response3.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    public void testConcurrent() throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());

        final ExecutorService executor = Executors.newWorkStealingPool();
        final OAuth2ClientCredentialsGrant grant = OAuth2ClientCredentialsGrant
                .builder(authClient, "/token/client/")
                .clientBasicAuthorization(() -> CLIENT_CREDENTIALS)
                .executor(executor).build();
        try (Server server = resourceServer.start()) {

            final WebClient client = WebClient.builder(resourceServer.httpUri())
                                              .decorator(OAuth2Client.newDecorator(grant))
                                              .build();

            final int count = 10;
            final ForkJoinPool pool = new ForkJoinPool(count);

            final List<HttpResponse> responses1 = makeGetRequests(client,
                                                                  "/resource-read-write/", count, pool);
            validateResponses(responses1, HttpStatus.OK);

            final List<HttpResponse> responses2 = makeGetRequests(client,
                                                                  "/resource-read/", count, pool);
            validateResponses(responses2, HttpStatus.OK);

            final List<HttpResponse> responses3 = makeGetRequests(client,
                                                                  "/resource-read-write-update/", count, pool);
            validateResponses(responses3, HttpStatus.FORBIDDEN);
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<HttpResponse> makeGetRequests(WebClient client, String resource, int count,
                                                      ForkJoinPool pool) {
        final Callable<HttpResponse> task = () -> client.get(resource);
        final List<Callable<HttpResponse>> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tasks.add(task);
        }
        return pool.invokeAll(tasks).parallelStream().map(f -> {
            try {
                return f.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    private static void validateResponses(List<HttpResponse> responses, HttpStatus status) {
        for (HttpResponse response : responses) {
            final AggregatedHttpResponse aggregate = response.aggregate().join();
            assertThat(aggregate.status()).isEqualTo(status);
        }
    }

    @Test
    public void testUnauthorized() {
        final WebClient authClient = WebClient.of(authServer.httpUri());

        final OAuth2ClientCredentialsGrant grant = OAuth2ClientCredentialsGrant
                .builder(authClient, "/token/client/")
                .clientBasicAuthorization(() -> SERVER_CREDENTIALS).build();
        try (Server server = resourceServer.start()) {

            final WebClient client = WebClient.builder(resourceServer.httpUri())
                                              .decorator(OAuth2Client.newDecorator(grant))
                                              .build();

            assertThatThrownBy(() -> client.get("/resource-read-write/").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .getCause().isInstanceOf(InvalidClientException.class);
        }
    }
}
