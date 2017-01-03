/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.auth;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.assertj.core.util.Strings;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.server.http.HttpService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.test.AbstractServerTest;

public class AuthServiceTest extends AbstractServerTest {

    private static final Encoder BASE64_ENCODER = Base64.getEncoder();

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        // Auth with arbitrary predicate
        Predicate<HttpHeaders> predicate = (HttpHeaders headers) -> {
            return "unit test".equals(headers.get(HttpHeaderNames.AUTHORIZATION));
        };
        sb.serviceAt(
                "/",
                new AbstractHttpService() {
                    @Override
                    protected void doGet(
                            ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                        res.respond(HttpStatus.OK);
                    }
                }.decorate(HttpAuthService.newDecorator(predicate))
                 .decorate(LoggingService::new));

        // Auth with HTTP basic
        final Map<String, String> usernameToPassword = ImmutableMap.of("brown", "cony", "pangyo", "choco");
        Predicate<BasicToken> httpBasicPredicate = (BasicToken token) -> {
            String username = token.username();
            String password = token.password();
            return password.equals(usernameToPassword.get(username));
        };
        sb.serviceAt(
                "/basic",
                new AbstractHttpService() {
                    @Override
                    protected void doGet(
                            ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                        res.respond(HttpStatus.OK);
                    }
                }.decorate(new HttpAuthServiceBuilder().addBasicAuth(httpBasicPredicate).newDecorator())
                 .decorate(LoggingService::new));

        // Auth with OAuth1a
        Predicate<OAuth1aToken> oAuth1aPredicate = (OAuth1aToken token) -> {
            return "dummy_signature".equals(token.signature());
        };
        sb.serviceAt(
                "/oauth1a",
                new AbstractHttpService() {
                    @Override
                    protected void doGet(
                            ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                        res.respond(HttpStatus.OK);
                    }
                }.decorate(new HttpAuthServiceBuilder().addOAuth1a(oAuth1aPredicate).newDecorator())
                 .decorate(LoggingService::new));

        // Auth with OAuth2
        Predicate<OAuth2Token> oAuth2aPredicate = (OAuth2Token token) -> {
            return "dummy_oauth2_token".equals(token.accessToken());
        };
        sb.serviceAt(
                "/oauth2",
                new AbstractHttpService() {
                    @Override
                    protected void doGet(
                            ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                        res.respond(HttpStatus.OK);
                    }
                }.decorate(new HttpAuthServiceBuilder().addOAuth2(oAuth2aPredicate).newDecorator())
                 .decorate(LoggingService::new));

        // Auth with all predicates above!
        HttpService compositeService = new AbstractHttpService() {
            @Override
            protected void doGet(
                    ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.respond(HttpStatus.OK);
            }
        };
        HttpAuthService compositeAuth = new HttpAuthServiceBuilder()
                .add(predicate)
                .addBasicAuth(httpBasicPredicate)
                .addOAuth1a(oAuth1aPredicate)
                .addOAuth2(oAuth2aPredicate)
                .build(compositeService);
        sb.serviceAt(
                "/composite", compositeAuth.decorate(LoggingService::new));
    }

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
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/basic")))) {
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
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/composite")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
            try (CloseableHttpResponse res = hc.execute(
                    basicGetRequest("/composite", BasicToken.of("choco", "pangyo")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 401 Unauthorized"));
            }
        }
    }

    private static HttpRequestBase getRequest(String path, String authorization) {
        HttpGet request = new HttpGet(uri(path));
        request.addHeader("Authorization", authorization);
        return request;
    }

    private static HttpRequestBase basicGetRequest(String path, BasicToken basicToken) {
        HttpGet request = new HttpGet(uri(path));
        request.addHeader("Authorization", "Basic " +
                                           BASE64_ENCODER.encodeToString(
                                                   (basicToken.username() + ':' + basicToken.password())
                                                           .getBytes(StandardCharsets.US_ASCII)));
        return request;
    }

    private static HttpRequestBase oauth1aGetRequest(String path, OAuth1aToken oAuth1aToken) {
        HttpGet request = new HttpGet(uri(path));
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
        authorization.append("\"");
        for (Entry<String, String> entry : oAuth1aToken.additionals().entrySet()) {
            authorization.append("\",");
            authorization.append(entry.getKey());
            authorization.append("=\"");
            authorization.append(entry.getValue());
            authorization.append("\"");
        }
        request.addHeader("Authorization", authorization.toString());
        return request;
    }

    private static HttpRequestBase oauth2GetRequest(String path, OAuth2Token oAuth2Token) {
        HttpGet request = new HttpGet(uri(path));
        request.addHeader("Authorization", "Bearer " + oAuth2Token.accessToken());
        return request;
    }
}
