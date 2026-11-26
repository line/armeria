/*
 * Copyright 2026 LINE Corporation
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

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AuthServiceRealmTest {

    private static final HttpService ok = (ctx, req) -> HttpResponse.of(HttpStatus.OK);

    private static final Authorizer<Object> denyAll = (ctx, token) -> completedFuture(false);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // No realm: no behavior change.
            sb.service("/no-realm",
                       ok.decorate(AuthService.builder()
                                              .addBasicAuth(denyAll)
                                              .addOAuth1a(denyAll)
                                              .addOAuth2(denyAll)
                                              .newDecorator()));

            // Realm only.
            sb.service("/basic",
                       ok.decorate(AuthService.builder().addBasicAuth(denyAll).realm("My Service")
                                              .newDecorator()));
            sb.service("/oauth1a",
                       ok.decorate(AuthService.builder().addOAuth1a(denyAll).realm("My Service")
                                              .newDecorator()));
            sb.service("/oauth2",
                       ok.decorate(AuthService.builder().addOAuth2(denyAll).realm("My Service")
                                              .newDecorator()));

            // Mixed schemes. The same scheme is challenged only once.
            sb.service("/mixed",
                       ok.decorate(AuthService.builder()
                                              .addBasicAuth(denyAll)
                                              .addOAuth2(denyAll)
                                              .addBasicAuth(denyAll)
                                              .realm("My Service")
                                              .newDecorator()));
            sb.service("/mixed-build",
                       AuthService.builder()
                                  .addBasicAuth(denyAll)
                                  .addOAuth1a(denyAll)
                                  .addOAuth2(denyAll)
                                  .realm("My Service")
                                  .build(ok));

            // The realm is escaped.
            sb.service("/escape",
                       ok.decorate(AuthService.builder().addBasicAuth(denyAll).realm("a\"b\\c")
                                              .newDecorator()));

            // Authorizers with a custom header and generic authorizers are not challenged.
            sb.service("/custom-header",
                       ok.decorate(AuthService.builder()
                                              .addBasicAuth(denyAll, "X-Custom-Authorization")
                                              .addOAuth1a(denyAll, "X-Custom-Authorization")
                                              .addOAuth2(denyAll, "X-Custom-Authorization")
                                              .realm("My Service")
                                              .newDecorator()));
            sb.service("/generic",
                       ok.decorate(AuthService.builder()
                                              .add((ctx, req) -> completedFuture(false))
                                              .realm("My Service")
                                              .newDecorator()));

            // An explicit onFailure() always wins, regardless of the order.
            sb.service("/on-failure-before",
                       ok.decorate(AuthService.builder()
                                              .onFailure((delegate, ctx, req, cause) -> {
                                                  return HttpResponse.of(HttpStatus.FORBIDDEN);
                                              })
                                              .addBasicAuth(denyAll)
                                              .realm("My Service")
                                              .newDecorator()));
            sb.service("/on-failure-after",
                       ok.decorate(AuthService.builder()
                                              .addBasicAuth(denyAll)
                                              .realm("My Service")
                                              .onFailure((delegate, ctx, req, cause) -> {
                                                  return HttpResponse.of(HttpStatus.FORBIDDEN);
                                              })
                                              .newDecorator()));

            // The failure handler of an authorizer takes precedence over the default one.
            final Authorizer<Object> authorizerWithHandler = new Authorizer<Object>() {
                @Override
                public CompletionStage<Boolean> authorize(
                        ServiceRequestContext ctx, Object data) {
                    return completedFuture(false);
                }

                @Override
                public CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(
                        ServiceRequestContext ctx, Object data) {
                    return completedFuture(AuthorizationStatus.ofFailure(
                            (delegate, ctx0, req, cause) -> HttpResponse.of(HttpStatus.FORBIDDEN)));
                }
            };
            sb.service("/authorizer-handler",
                       ok.decorate(AuthService.builder().addBasicAuth(authorizerWithHandler)
                                              .realm("My Service").newDecorator()));

            // An exception is handled by the default handler.
            sb.service("/exception",
                       ok.decorate(AuthService.builder()
                                              .addBasicAuth((ctx, token) -> {
                                                  return UnmodifiableFuture.exceptionallyCompletedFuture(
                                                          new AnticipatedException());
                                              })
                                              .realm("My Service")
                                              .newDecorator()));
        }
    };

    private static AggregatedHttpResponse get(String path) {
        // A request which has no credentials is rejected by every authorizer.
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        return client.get(path);
    }

    @Test
    void noChallengeWithoutRealm() {
        final AggregatedHttpResponse res = get("/no-realm");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Same response as before: a plain-text body.
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo(HttpStatus.UNAUTHORIZED.toHttpData().toStringUtf8());
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE)).isEmpty();
    }

    @Test
    void basicChallenge() {
        final AggregatedHttpResponse res = get("/basic");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo(HttpStatus.UNAUTHORIZED.toHttpData().toStringUtf8());
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Basic realm=\"My Service\", charset=\"UTF-8\"");
    }

    @Test
    void oAuth1aChallenge() {
        final AggregatedHttpResponse res = get("/oauth1a");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("OAuth realm=\"My Service\"");
    }

    @Test
    void oAuth2Challenge() {
        final AggregatedHttpResponse res = get("/oauth2");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Bearer realm=\"My Service\"");
    }

    @Test
    void mixedSchemesHaveOneChallengePerScheme() {
        AggregatedHttpResponse res = get("/mixed");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Basic realm=\"My Service\", charset=\"UTF-8\"",
                                 "Bearer realm=\"My Service\"");

        res = get("/mixed-build");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Basic realm=\"My Service\", charset=\"UTF-8\"",
                                 "OAuth realm=\"My Service\"",
                                 "Bearer realm=\"My Service\"");
    }

    @Test
    void realmIsEscaped() {
        final AggregatedHttpResponse res = get("/escape");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Basic realm=\"a\\\"b\\\\c\", charset=\"UTF-8\"");
    }

    @Test
    void noChallengeForCustomHeaderOrGenericAuthorizer() {
        for (String path : new String[] { "/custom-header", "/generic" }) {
            final AggregatedHttpResponse res = get(path);
            assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE)).isEmpty();
        }
    }

    @Test
    void explicitOnFailureWins() {
        for (String path : new String[] { "/on-failure-before", "/on-failure-after" }) {
            final AggregatedHttpResponse res = get(path);
            assertThat(res.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE)).isEmpty();
        }
    }

    @Test
    void authorizerFailureHandlerWins() {
        final AggregatedHttpResponse res = get("/authorizer-handler");
        assertThat(res.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE)).isEmpty();
    }

    @Test
    void challengeOnException() {
        // The authorizer is invoked only when a token is extracted, so send well-formed credentials.
        final AggregatedHttpResponse res =
                WebClient.builder(server.httpUri())
                         .auth(AuthToken.ofBasic("brown", "cony"))
                         .build()
                         .blocking()
                         .get("/exception");
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Basic realm=\"My Service\", charset=\"UTF-8\"");
    }

    @Test
    void shouldNotRecordSchemeWhenAuthorizerIsNull() {
        final AuthServiceBuilder builder = AuthService.builder();
        assertThatThrownBy(() -> builder.addBasicAuth(null)).isInstanceOf(NullPointerException.class);
        // Nothing was added, so no authorizer is available and no challenge can leak from a failed call.
        builder.add((ctx, req) -> completedFuture(false)).realm("My Service");
        assertThat(builder.build(ok)).isNotNull();
    }

    @Test
    void realmMustNotBeNull() {
        assertThatThrownBy(() -> AuthService.builder().realm(null))
                .isInstanceOf(NullPointerException.class);
    }
}
