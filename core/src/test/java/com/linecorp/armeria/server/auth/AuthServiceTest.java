/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.auth;

import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.assertj.core.util.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AsciiString;

class AuthServiceTest {

    private static final Encoder BASE64_ENCODER = Base64.getEncoder();

    private static class InsecureToken {
        String accessToken() {
            return "all your tokens are belong to us";
        }
    }

    private static final Function<HttpHeaders, InsecureToken> INSECURE_TOKEN_EXTRACTOR =
            headers -> new InsecureToken();

    private static final AsciiString CUSTOM_TOKEN_HEADER = HttpHeaderNames.of("X-Custom-Authorization");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService ok = new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            };

            // Auth with arbitrary authorizer
            final Authorizer<HttpRequest> authorizer = (ctx, req) ->
                    CompletableFuture.supplyAsync(
                            () -> "unit test".equals(req.headers().get(AUTHORIZATION)));
            sb.service(
                    "/",
                    ok.decorate(AuthService.newDecorator(authorizer))
                      .decorate(LoggingService.newDecorator()));

            // Auth with HTTP basic
            final Map<String, String> usernameToPassword = ImmutableMap.of("brown", "cony", "pangyo", "choco");
            final Authorizer<BasicToken> httpBasicAuthorizer = (ctx, token) -> {
                final String username = token.username();
                final String password = token.password();
                return completedFuture(password.equals(usernameToPassword.get(username)));
            };
            sb.service(
                    "/basic",
                    ok.decorate(AuthService.builder().addBasicAuth(httpBasicAuthorizer).newDecorator())
                      .decorate(LoggingService.newDecorator()));
            sb.service(
                    "/basic-custom",
                    ok.decorate(AuthService.builder()
                                           .addBasicAuth(httpBasicAuthorizer, CUSTOM_TOKEN_HEADER)
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with OAuth1a
            final Authorizer<OAuth1aToken> oAuth1aAuthorizer = (ctx, token) ->
                    completedFuture("dummy_signature".equals(token.signature()) &&
                                    "dummy_consumer_key@#$!".equals(token.consumerKey()));
            sb.service(
                    "/oauth1a",
                    ok.decorate(AuthService.builder().addOAuth1a(oAuth1aAuthorizer).newDecorator())
                      .decorate(LoggingService.newDecorator()));
            sb.service(
                    "/oauth1a-custom",
                    ok.decorate(AuthService.builder().addOAuth1a(oAuth1aAuthorizer, CUSTOM_TOKEN_HEADER)
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with OAuth2
            final Authorizer<OAuth2Token> oAuth2Authorizer = (ctx, token) ->
                    completedFuture("dummy_oauth2_token".equals(token.accessToken()));
            sb.service(
                    "/oauth2",
                    ok.decorate(AuthService.builder().addOAuth2(oAuth2Authorizer).newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with OAuth2 on custom header
            sb.service(
                    "/oauth2-custom",
                    ok.decorate(AuthService.builder()
                                           .addOAuth2(oAuth2Authorizer, CUSTOM_TOKEN_HEADER)
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with arbitrary token extractor
            final Authorizer<InsecureToken> insecureTokenAuthorizer = (ctx, token) ->
                    completedFuture(new InsecureToken().accessToken().equals(token.accessToken()));
            sb.service("/insecuretoken",
                       ok.decorate(AuthService.builder()
                                              .addTokenAuthorizer(INSECURE_TOKEN_EXTRACTOR,
                                                                  insecureTokenAuthorizer)
                                              .newDecorator())
                         .decorate(LoggingService.newDecorator()));

            // Auth with all predicates above!
            sb.service(
                    "/composite",
                    AuthService.builder().add(authorizer)
                               .addBasicAuth(httpBasicAuthorizer)
                               .addOAuth1a(oAuth1aAuthorizer)
                               .addOAuth2(oAuth2Authorizer)
                               .build(ok)
                               .decorate(LoggingService.newDecorator()));

            // Authorizer fails with an exception.
            sb.service(
                    "/authorizer_exception",
                    ok.decorate(AuthService.builder().add((ctx, data) -> {
                        throw new AnticipatedException("bug!");
                    }).newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Authorizer returns a future that resolves to null.
            sb.service(
                    "/authorizer_resolve_null",
                    ok.decorate(AuthService.builder().add((ctx, data) -> completedFuture(null))
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Authorizer returns null.
            sb.service(
                    "/authorizer_null",
                    ok.decorate(AuthService.builder().add((ctx, data) -> null)
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // AuthService fails when building a success message.
            sb.service(
                    "/on_success_exception",
                    ok.decorate(AuthService.builder().add((ctx, req) -> completedFuture(true))
                                           .onSuccess((delegate, ctx, req) -> {
                                               throw new AnticipatedException("bug!");
                                           })
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // AuthService fails when building a failure message.
            sb.service(
                    "/on_failure_exception",
                    ok.decorate(AuthService.builder().add((ctx, req) -> completedFuture(false))
                                           .onFailure((delegate, ctx, req, cause) -> {
                                               throw new AnticipatedException("bug!");
                                           })
                                           .newDecorator())
                      .decorate(LoggingService.newDecorator()));
        }
    };

    @Test
    void testAuth() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    getRequest("/", "unit test"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(
                    getRequest("/", "UNIT TEST"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testBasicAuth() throws Exception {
        final WebClient webClient = WebClient.builder(server.httpUri())
                                             .auth(AuthToken.ofBasic("brown", "cony"))
                                             .build();
        assertThat(webClient.get("/basic").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/basic", AuthToken.ofBasic("pangyo", "choco"),
                                    AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/basic-custom", AuthToken.ofBasic("brown", "cony"),
                                    CUSTOM_TOKEN_HEADER))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/basic"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/basic", AuthToken.ofBasic("choco", "pangyo"),
                                    AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testOAuth1a() throws Exception {
        final OAuth1aToken passToken = AuthToken.builderForOAuth1a()
                                                .realm("dummy_realm")
                                                .consumerKey("dummy_consumer_key@#$!")
                                                .token("dummy_oauth1a_token")
                                                .signatureMethod("dummy")
                                                .signature("dummy_signature")
                                                .timestamp("0")
                                                .nonce("dummy_nonce")
                                                .version("1.0")
                                                .build();
        final WebClient webClient = WebClient.builder(server.httpUri())
                                             .auth(passToken)
                                             .build();
        assertThat(webClient.get("/oauth1a").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    oauth1aGetRequest("/oauth1a-custom", passToken, CUSTOM_TOKEN_HEADER))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            final OAuth1aToken failToken = AuthToken.builderForOAuth1a()
                                                    .realm("dummy_realm")
                                                    .consumerKey("dummy_consumer_key@#$!")
                                                    .token("dummy_oauth1a_token")
                                                    .signatureMethod("dummy")
                                                    .signature("DUMMY_signature") // This is different.
                                                    .timestamp("0")
                                                    .nonce("dummy_nonce")
                                                    .version("1.0")
                                                    .build();
            try (CloseableHttpResponse res = hc.execute(
                    oauth1aGetRequest("/oauth1a", failToken, AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testOAuth2() throws Exception {
        final WebClient webClient = WebClient.builder(server.httpUri())
                                             .auth(AuthToken.ofOAuth2("dummy_oauth2_token"))
                                             .build();
        assertThat(webClient.get("/oauth2").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/oauth2-custom", AuthToken.ofOAuth2("dummy_oauth2_token"),
                                     CUSTOM_TOKEN_HEADER))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/oauth2", AuthToken.ofOAuth2("DUMMY_oauth2_token"),
                                     AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testArbitraryToken() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/insecuretoken",
                                     AuthToken.ofOAuth2("all your tokens are belong to us"),
                                     AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
        }
    }

    @Test
    void testCompositeAuth() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    getRequest("/composite", "unit test"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/composite", AuthToken.ofBasic("brown", "cony"),
                                    AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            final Map<String, String> passToken = ImmutableMap.<String, String>builder()
                    .put("realm", "dummy_realm")
                    .put("oauth_consumer_key", "dummy_consumer_key@#$!")
                    .put("oauth_token", "dummy_oauth1a_token")
                    .put("oauth_signature_method", "dummy")
                    .put("oauth_signature", "dummy_signature")
                    .put("oauth_timestamp", "0")
                    .put("oauth_nonce", "dummy_nonce")
                    .put("version", "1.0")
                    .build();
            final OAuth1aToken oAuth1aToken = AuthToken.builderForOAuth1a().putAll(passToken).build();
            try (CloseableHttpResponse res = hc.execute(
                    oauth1aGetRequest("/composite", oAuth1aToken, AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/composite", AuthToken.ofOAuth2("dummy_oauth2_token"),
                                     AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/composite"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/composite",
                                    AuthToken.ofBasic("choco", "pangyo"), AUTHORIZATION))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testAuthorizerException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(server.httpUri() + "/authorizer_exception"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testAuthorizerResolveNull() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(server.httpUri() + "/authorizer_resolve_null"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testAuthorizerNull() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(server.httpUri() + "/authorizer_null"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 401 Unauthorized");
            }
        }
    }

    @Test
    void testOnSuccessException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(server.httpUri() + "/on_success_exception"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 500 Internal Server Error");
            }
        }
    }

    @Test
    void testOnFailureException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(server.httpUri() + "/on_failure_exception"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 500 Internal Server Error");
            }
        }
    }

    private static HttpRequestBase getRequest(String path, String authorization) {
        final HttpGet request = new HttpGet(server.httpUri().resolve(path));
        request.addHeader("Authorization", authorization);
        return request;
    }

    private static HttpRequestBase basicGetRequest(String path, BasicToken basicToken, AsciiString header) {
        final HttpGet request = new HttpGet(server.httpUri().resolve(path));
        request.addHeader(header.toString(), "Basic " +
                                             BASE64_ENCODER.encodeToString(
                                                     (basicToken.username() + ':' + basicToken.password())
                                                             .getBytes(StandardCharsets.US_ASCII)));
        return request;
    }

    private static HttpRequestBase oauth1aGetRequest(
            String path, OAuth1aToken oAuth1aToken, AsciiString header) {
        final HttpGet request = new HttpGet(server.httpUri().resolve(path));
        final StringBuilder authorization = new StringBuilder("OAuth ");
        final String realm = oAuth1aToken.realm();
        if (!Strings.isNullOrEmpty(realm)) {
            authorization.append("realm=\"");
            authorization.append(realm);
            authorization.append("\",");
        }
        authorization.append("oauth_consumer_key=\"");
        authorization.append(oAuth1aToken.consumerKey());
        authorization.append("\",oauth_token=\"");
        authorization.append(oAuth1aToken.token());
        authorization.append("\",oauth_signature_method=\"");
        authorization.append(oAuth1aToken.signatureMethod());
        authorization.append("\",oauth_signature=\"");
        authorization.append(oAuth1aToken.signature());
        authorization.append("\",oauth_timestamp=\"");
        authorization.append(oAuth1aToken.timestamp());
        authorization.append("\",oauth_nonce=\"");
        authorization.append(oAuth1aToken.nonce());
        authorization.append("\",version=\"");
        authorization.append(oAuth1aToken.version());
        authorization.append('"');
        for (Entry<String, String> entry : oAuth1aToken.additionals().entrySet()) {
            authorization.append("\",");
            authorization.append(entry.getKey());
            authorization.append("=\"");
            authorization.append(entry.getValue());
            authorization.append('"');
        }
        request.addHeader(header.toString(), authorization.toString());
        return request;
    }

    private static HttpRequestBase oauth2GetRequest(String path, OAuth2Token oAuth2Token, AsciiString header) {
        final HttpGet request = new HttpGet(server.httpUri().resolve(path));
        request.addHeader(header.toString(), "Bearer " + oAuth2Token.accessToken());
        return request;
    }
}
