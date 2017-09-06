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

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.assertj.core.util.Strings;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.internal.AnticipatedException;
import com.linecorp.armeria.testing.server.ServerRule;

public class HttpAuthServiceTest {

    private static final Encoder BASE64_ENCODER = Base64.getEncoder();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService ok = new AbstractHttpService() {
                @Override
                protected void doGet(
                        ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }
            };

            // Auth with arbitrary authorizer
            Authorizer<HttpRequest> authorizer = (ctx, req) ->
                    CompletableFuture.supplyAsync(
                            () -> "unit test".equals(req.headers().get(HttpHeaderNames.AUTHORIZATION)));
            sb.service(
                    "/",
                    ok.decorate(HttpAuthService.newDecorator(authorizer))
                      .decorate(LoggingService.newDecorator()));

            // Auth with HTTP basic
            final Map<String, String> usernameToPassword = ImmutableMap.of("brown", "cony", "pangyo", "choco");
            Authorizer<BasicToken> httpBasicAuthorizer = (ctx, token) -> {
                String username = token.username();
                String password = token.password();
                return completedFuture(password.equals(usernameToPassword.get(username)));
            };
            sb.service(
                    "/basic",
                    ok.decorate(new HttpAuthServiceBuilder().addBasicAuth(httpBasicAuthorizer).newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with OAuth1a
            Authorizer<OAuth1aToken> oAuth1aAuthorizer = (ctx, token) ->
                    completedFuture("dummy_signature".equals(token.signature()));
            sb.service(
                    "/oauth1a",
                    ok.decorate(new HttpAuthServiceBuilder().addOAuth1a(oAuth1aAuthorizer).newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with OAuth2
            Authorizer<OAuth2Token> oAuth2aAuthorizer = (ctx, token) ->
                    completedFuture("dummy_oauth2_token".equals(token.accessToken()));
            sb.service(
                    "/oauth2",
                    ok.decorate(new HttpAuthServiceBuilder().addOAuth2(oAuth2aAuthorizer).newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // Auth with all predicates above!
            sb.service(
                    "/composite",
                    new HttpAuthServiceBuilder().add(authorizer)
                                                .addBasicAuth(httpBasicAuthorizer)
                                                .addOAuth1a(oAuth1aAuthorizer)
                                                .addOAuth2(oAuth2aAuthorizer)
                                                .build(ok)
                                                .decorate(LoggingService.newDecorator()));

            // Authorizer fails with an exception.
            sb.service(
                    "/authorizer_exception",
                    ok.decorate(new HttpAuthServiceBuilder().add((ctx, data) -> {
                        throw new AnticipatedException("bug!");
                    }).newDecorator())
                      .decorate(LoggingService.newDecorator()));

            // AuthService fails when building a success message.
            sb.service(
                    "/on_success_exception",
                    ok.decorate(service -> new HttpAuthService(service) {
                        @Override
                        protected CompletionStage<Boolean> authorize(HttpRequest request,
                                                                     ServiceRequestContext ctx) {
                            return completedFuture(true);
                        }

                        @Override
                        protected HttpResponse onSuccess(ServiceRequestContext ctx, HttpRequest req) {
                            throw new AnticipatedException("bug!");
                        }
                    }).decorate(LoggingService.newDecorator()));
        }
    };

    @Test
    public void testAuth() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    getRequest("/", "unit test"))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    getRequest("/", "UNIT TEST"))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    @Test
    public void testBasicAuth() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/basic", BasicToken.of("brown", "cony")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/basic", BasicToken.of("pangyo", "choco")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/basic")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/basic", BasicToken.of("choco", "pangyo")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    @Test
    public void testOAuth1a() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            Map<String, String> passToken = ImmutableMap.<String, String>builder()
                    .put("realm", "dummy_realm")
                    .put("oauth_consumer_key", "dummy_consumer_key")
                    .put("oauth_token", "dummy_oauth1a_token")
                    .put("oauth_signature_method", "dummy")
                    .put("oauth_signature", "dummy_signature")
                    .put("oauth_timestamp", "0")
                    .put("oauth_nonce", "dummy_nonce")
                    .put("version", "1.0")
                    .build();
            try (CloseableHttpResponse res = hc.execute(
                    oauth1aGetRequest("/oauth1a", OAuth1aToken.of(passToken)))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            Map<String, String> failToken = ImmutableMap.<String, String>builder()
                    .put("realm", "dummy_realm")
                    .put("oauth_consumer_key", "dummy_consumer_key")
                    .put("oauth_token", "dummy_oauth1a_token")
                    .put("oauth_signature_method", "dummy")
                    .put("oauth_signature", "DUMMY_signature")
                    .put("oauth_timestamp", "0")
                    .put("oauth_nonce", "dummy_nonce")
                    .put("version", "1.0")
                    .build();
            try (CloseableHttpResponse res = hc.execute(
                    oauth1aGetRequest("/oauth1a", OAuth1aToken.of(failToken)))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    @Test
    public void testOAuth2() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/oauth2", OAuth2Token.of("dummy_oauth2_token")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/oauth2", OAuth2Token.of("DUMMY_oauth2_token")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    @Test
    public void testCompositeAuth() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    getRequest("/composite", "unit test"))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/composite", BasicToken.of("brown", "cony")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            Map<String, String> passToken = ImmutableMap.<String, String>builder()
                    .put("realm", "dummy_realm")
                    .put("oauth_consumer_key", "dummy_consumer_key")
                    .put("oauth_token", "dummy_oauth1a_token")
                    .put("oauth_signature_method", "dummy")
                    .put("oauth_signature", "dummy_signature")
                    .put("oauth_timestamp", "0")
                    .put("oauth_nonce", "dummy_nonce")
                    .put("version", "1.0")
                    .build();
            try (CloseableHttpResponse res = hc.execute(
                    oauth1aGetRequest("/composite", OAuth1aToken.of(passToken)))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    oauth2GetRequest("/composite", OAuth2Token.of("dummy_oauth2_token")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/composite")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/composite", BasicToken.of("choco", "pangyo")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    @Test
    public void testAuthorizerException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/authorizer_exception")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    @Test
    public void testOnSuccessException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/on_success_exception")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
        }
    }

    private static HttpRequestBase getRequest(String path, String authorization) {
        HttpGet request = new HttpGet(server.uri(path));
        request.addHeader("Authorization", authorization);
        return request;
    }

    private static HttpRequestBase basicGetRequest(String path, BasicToken basicToken) {
        HttpGet request = new HttpGet(server.uri(path));
        request.addHeader("Authorization", "Basic " +
                                           BASE64_ENCODER.encodeToString(
                                                   (basicToken.username() + ':' + basicToken.password())
                                                           .getBytes(StandardCharsets.US_ASCII)));
        return request;
    }

    private static HttpRequestBase oauth1aGetRequest(String path, OAuth1aToken oAuth1aToken) {
        HttpGet request = new HttpGet(server.uri(path));
        StringBuilder authorization = new StringBuilder("OAuth ");
        String realm = oAuth1aToken.realm();
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
        request.addHeader("Authorization", authorization.toString());
        return request;
    }

    private static HttpRequestBase oauth2GetRequest(String path, OAuth2Token oAuth2Token) {
        HttpGet request = new HttpGet(server.uri(path));
        request.addHeader("Authorization", "Bearer " + oAuth2Token.accessToken());
        return request;
    }
}
