/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.athenz;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.interfaces.RSAPublicKey;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import com.yahoo.athenz.auth.token.jwts.ArmeriaJwtsSigningKeyResolver;

class ArmeriaJwtsSigningKeyResolverTest {

    private static final String KEY_ID = "test-key-1";

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension cert = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final RSAKey rsaJwk = new RSAKey.Builder(
                    (RSAPublicKey) cert.certificate().getPublicKey())
                    .keyID(KEY_ID)
                    .build();
            final String jwksJson = new JWKSet(rsaJwk).toString();

            sb.service("/oauth2/keys", (ctx, req) ->
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, jwksJson));
            sb.service("/oauth2/error", (ctx, req) ->
                    HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    };

    @Test
    void shouldResolvePublicKey() {
        final WebClient webClient = WebClient.of(server.httpUri());
        final ArmeriaJwtsSigningKeyResolver resolver =
                ArmeriaJwtsSigningKeyResolver.create(webClient, "/oauth2/keys");

        assertThat(resolver.getPublicKey(KEY_ID))
                .isEqualTo(cert.certificate().getPublicKey());
    }

    @Test
    void shouldReturnNullForUnknownKeyId() {
        final WebClient webClient = WebClient.of(server.httpUri());
        final ArmeriaJwtsSigningKeyResolver resolver =
                ArmeriaJwtsSigningKeyResolver.create(webClient, "/oauth2/keys");

        assertThat(resolver.getPublicKey("nonexistent-key")).isNull();
    }

    @Test
    void shouldHandleServerError() {
        final WebClient webClient = WebClient.of(server.httpUri());
        final ArmeriaJwtsSigningKeyResolver resolver =
                ArmeriaJwtsSigningKeyResolver.create(webClient, "/oauth2/error");

        // The resolver logs errors internally but does not throw.
        assertThat(resolver.getPublicKey(KEY_ID)).isNull();
    }
}
