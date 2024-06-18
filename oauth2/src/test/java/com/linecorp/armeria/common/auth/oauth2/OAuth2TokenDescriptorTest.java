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

package com.linecorp.armeria.common.auth.oauth2;

import static com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor.SCOPE_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.annotation.Nullable;

public class OAuth2TokenDescriptorTest {

    @Test
    void testBuilder() throws Exception {
        final boolean active = true;
        final String[] scope = { "read", "write" };
        final String clientId = "l238j323ds-23ij4";
        final String userName = "jdoe";
        final String tokenType = "bearer";
        final Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant expiresAt = issuedAt.plus(expiresIn);
        final String subject = "Z5O3upPC88QrAjx00dis";
        final String audience = "https://protected.example.net/resource";
        final String issuer = "https://server.example.com/";
        final String jwtId = "12345";
        final Map<String, String> extras = Collections.singletonMap("extension_field", "twenty-seven");
        final String rawResponse =
                "{\"active\":true," +
                "\"scope\":\"read write\"," +
                "\"client_id\":\"l238j323ds-23ij4\"," +
                "\"username\":\"jdoe\"," +
                "\"token_type\":\"bearer\"," +
                "\"exp\":" + expiresAt.getEpochSecond() + ',' +
                "\"iat\":" + issuedAt.getEpochSecond() + ',' +
                "\"nbf\":" + issuedAt.getEpochSecond() + ',' +
                "\"sub\":\"Z5O3upPC88QrAjx00dis\"," +
                "\"aud\":\"https://protected.example.net/resource\"," +
                "\"iss\":\"https://server.example.com/\"," +
                "\"jti\":\"12345\"," +
                "\"extension_field\":\"twenty-seven\"}";

        final OAuth2TokenDescriptor descriptor = OAuth2TokenDescriptor.builder(active)
                                                                      .clientId(clientId)
                                                                      .userName(userName)
                                                                      .tokenType(tokenType)
                                                                      .expiresAt(expiresAt)
                                                                      .issuedAt(issuedAt)
                                                                      .notBefore(issuedAt)
                                                                      .subject(subject)
                                                                      .audience(audience)
                                                                      .issuer(issuer)
                                                                      .jwtId(jwtId)
                                                                      .extras(extras)
                                                                      .scope(scope)
                                                                      .build();

        assertThat(descriptor.isActive()).isEqualTo(active);
        assertThat(descriptor.scope()).isEqualTo(toScopeString(scope));
        assertThat(descriptor.scopeSet()).contains(scope);
        assertThat(descriptor.scopeSet()).doesNotContain("foo");
        assertThat(descriptor.scopeSet()).containsExactly(scope);
        assertThat(descriptor.clientId()).isEqualTo(clientId);
        assertThat(descriptor.userName()).isEqualTo(userName);
        assertThat(descriptor.tokenType()).isEqualTo(tokenType);
        assertThat(descriptor.expiresAt()).isEqualTo(expiresAt);
        assertThat(descriptor.issuedAt()).isEqualTo(issuedAt);
        assertThat(descriptor.notBefore()).isEqualTo(issuedAt);
        assertThat(descriptor.isValid()).isTrue();
        assertThat(descriptor.isValid(issuedAt.plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(descriptor.subject()).isEqualTo(subject);
        assertThat(descriptor.audience()).isEqualTo(audience);
        assertThat(descriptor.issuer()).isEqualTo(issuer);
        assertThat(descriptor.jwtId()).isEqualTo(jwtId);
        assertThat(descriptor.extras()).isEqualTo(extras);
        assertThat(descriptor.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testOfRawResponse() throws Exception {
        final boolean active = true;
        final String[] scope = { "read", "write" };
        final String clientId = "l238j323ds-23ij4";
        final String userName = "jdoe";
        final String tokenType = "bearer";
        final Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant expiresAt = issuedAt.plus(expiresIn);
        final String subject = "Z5O3upPC88QrAjx00dis";
        final String audience = "https://protected.example.net/resource";
        final String issuer = "https://server.example.com/";
        final String jwtId = "12345";
        final Map<String, String> extras = Collections.singletonMap("extension_field", "twenty-seven");
        final String rawResponse =
                "{\"active\":true," +
                "\"scope\":\"read write\"," +
                "\"client_id\":\"l238j323ds-23ij4\"," +
                "\"username\":\"jdoe\"," +
                "\"token_type\":\"bearer\"," +
                "\"exp\":" + expiresAt.getEpochSecond() + ',' +
                "\"iat\":" + issuedAt.getEpochSecond() + ',' +
                "\"nbf\":" + issuedAt.getEpochSecond() + ',' +
                "\"sub\":\"Z5O3upPC88QrAjx00dis\"," +
                "\"aud\":\"https://protected.example.net/resource\"," +
                "\"iss\":\"https://server.example.com/\"," +
                "\"jti\":\"12345\"," +
                "\"extension_field\":\"twenty-seven\"}";

        final OAuth2TokenDescriptor descriptor = OAuth2TokenDescriptor.parse(rawResponse);

        Thread.sleep(100);

        assertThat(descriptor.isActive()).isEqualTo(active);
        assertThat(descriptor.scope()).isEqualTo(toScopeString(scope));
        assertThat(descriptor.scopeSet()).contains(scope);
        assertThat(descriptor.scopeSet()).doesNotContain("foo");
        assertThat(descriptor.scopeSet()).containsExactly(scope);
        assertThat(descriptor.clientId()).isEqualTo(clientId);
        assertThat(descriptor.userName()).isEqualTo(userName);
        assertThat(descriptor.tokenType()).isEqualTo(tokenType);
        assertThat(descriptor.expiresAt()).isEqualTo(expiresAt);
        assertThat(descriptor.issuedAt()).isBefore(Instant.now());
        assertThat(descriptor.issuedAt()).isEqualTo(issuedAt);
        assertThat(descriptor.notBefore()).isEqualTo(issuedAt);
        assertThat(descriptor.isValid()).isTrue();
        assertThat(descriptor.isValid(issuedAt.plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(descriptor.subject()).isEqualTo(subject);
        assertThat(descriptor.audience()).isEqualTo(audience);
        assertThat(descriptor.issuer()).isEqualTo(issuer);
        assertThat(descriptor.jwtId()).isEqualTo(jwtId);
        assertThat(descriptor.extras()).isEqualTo(extras);
        assertThat(descriptor.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testToString() throws Exception {
        final boolean active = true;
        final String[] scope = { "read", "write" };
        final String clientId = "l238j323ds-23ij4";
        final String userName = "jdoe";
        final String tokenType = "bearer";
        final Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant expiresAt = issuedAt.plus(expiresIn);
        final String subject = "Z5O3upPC88QrAjx00dis";
        final String audience = "https://protected.example.net/resource";
        final String issuer = "https://server.example.com/";
        final String jwtId = "12345";
        final Map<String, String> extras = Collections.singletonMap("extension_field", "twenty-seven");
        final String rawResponse =
                "{\"active\":true," +
                "\"scope\":\"read write\"," +
                "\"client_id\":\"l238j323ds-23ij4\"," +
                "\"username\":\"jdoe\"," +
                "\"token_type\":\"bearer\"," +
                "\"exp\":" + expiresAt.getEpochSecond() + ',' +
                "\"iat\":" + issuedAt.getEpochSecond() + ',' +
                "\"nbf\":" + issuedAt.getEpochSecond() + ',' +
                "\"sub\":\"Z5O3upPC88QrAjx00dis\"," +
                "\"aud\":\"https://protected.example.net/resource\"," +
                "\"iss\":\"https://server.example.com/\"," +
                "\"jti\":\"12345\"," +
                "\"extension_field\":\"twenty-seven\"}";

        final OAuth2TokenDescriptor descriptor = OAuth2TokenDescriptor.builder(active)
                                                                      .clientId(clientId)
                                                                      .userName(userName)
                                                                      .tokenType(tokenType)
                                                                      .expiresAt(expiresAt)
                                                                      .issuedAt(issuedAt)
                                                                      .notBefore(issuedAt)
                                                                      .subject(subject)
                                                                      .audience(audience)
                                                                      .issuer(issuer)
                                                                      .jwtId(jwtId)
                                                                      .extras(extras)
                                                                      .scope(scope)
                                                                      .build();
        assertThat(descriptor.toString()).isEqualTo(rawResponse);
    }

    @Test
    void testEquals() throws Exception {
        final boolean active = true;
        final String[] scope = { "read", "write" };
        final String clientId = "l238j323ds-23ij4";
        final String userName = "jdoe";
        final String tokenType = "bearer";
        final Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant expiresAt = issuedAt.plus(expiresIn);
        final String subject = "Z5O3upPC88QrAjx00dis";
        final String audience = "https://protected.example.net/resource";
        final String issuer = "https://server.example.com/";
        final String jwtId = "12345";
        final Map<String, String> extras = Collections.singletonMap("extension_field", "twenty-seven");

        final OAuth2TokenDescriptor descriptor1 = OAuth2TokenDescriptor.builder(active)
                                                                       .clientId(clientId)
                                                                       .userName(userName)
                                                                       .tokenType(tokenType)
                                                                       .expiresAt(expiresAt)
                                                                       .issuedAt(issuedAt)
                                                                       .notBefore(issuedAt)
                                                                       .subject(subject)
                                                                       .audience(audience)
                                                                       .issuer(issuer)
                                                                       .jwtId(jwtId)
                                                                       .extras(extras)
                                                                       .scope(scope)
                                                                       .build();

        final OAuth2TokenDescriptor descriptor2 = OAuth2TokenDescriptor.builder(active)
                                                                       .clientId(clientId)
                                                                       .userName(userName)
                                                                       .tokenType(tokenType)
                                                                       .expiresAt(expiresAt)
                                                                       .issuedAt(issuedAt)
                                                                       .notBefore(issuedAt)
                                                                       .subject(subject)
                                                                       .audience(audience)
                                                                       .issuer(issuer)
                                                                       .jwtId(jwtId)
                                                                       .extras(extras)
                                                                       .scope(scope)
                                                                       .build();

        assertThat(descriptor2).isEqualTo(descriptor1);

        final OAuth2TokenDescriptor descriptor3 = OAuth2TokenDescriptor.builder(active)
                                                                       .clientId(clientId)
                                                                       .userName(userName)
                                                                       .tokenType(tokenType)
                                                                       .expiresAt(expiresAt)
                                                                       .issuedAt(issuedAt)
                                                                       .notBefore(issuedAt)
                                                                       .subject(subject)
                                                                       .audience(audience)
                                                                       .issuer(issuer)
                                                                       .jwtId(jwtId)
                                                                       .extras(extras)
                                                                       .scope("read")
                                                                       .build();

        assertThat(descriptor3).isNotEqualTo(descriptor1);

        final OAuth2TokenDescriptor descriptor4 = OAuth2TokenDescriptor.builder(active)
                                                                       .clientId(clientId)
                                                                       .userName(userName)
                                                                       .tokenType(tokenType)
                                                                       .expiresAt(expiresAt)
                                                                       .issuedAt(issuedAt)
                                                                       .notBefore(issuedAt)
                                                                       .subject(subject)
                                                                       .audience(audience)
                                                                       .issuer(issuer)
                                                                       .jwtId("6789")
                                                                       .extras(extras)
                                                                       .scope(scope)
                                                                       .build();

        assertThat(descriptor4).isNotEqualTo(descriptor1);
    }

    @Nullable
    private static String toScopeString(String... scopes) {
        if (scopes.length == 0) {
            return null;
        }
        return String.join(SCOPE_SEPARATOR, scopes);
    }
}
