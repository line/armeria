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

import static com.linecorp.armeria.common.auth.oauth2.AccessTokenCapsule.SCOPE_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

public class AccessTokenCapsuleTest {

    @Test
    void testBuilder() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Instant issuedAt = Instant.now();
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String rawResponse =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";

        final AccessTokenCapsule capsule = AccessTokenCapsule.builder(accessToken)
                                                             .tokenType(tokenType)
                                                             .issuedAt(issuedAt)
                                                             .expiresIn(expiresIn)
                                                             .refreshToken(refreshToken)
                                                             .extras(extras)
                                                             .scope(scope)
                                                             .build();

        assertThat(capsule.accessToken()).isEqualTo(accessToken);
        assertThat(capsule.tokenType()).isEqualTo(tokenType);
        assertThat(capsule.issuedAt()).isEqualTo(issuedAt);
        assertThat(capsule.expiresIn()).isEqualTo(expiresIn);
        assertThat(capsule.expiresAt()).isEqualTo(issuedAt.plus(expiresIn));
        assertThat(capsule.isValid()).isTrue();
        assertThat(capsule.isValid(issuedAt.plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(capsule.authorization()).isEqualTo("Bearer " + accessToken);
        assertThat(capsule.refreshToken()).isEqualTo(refreshToken);
        assertThat(capsule.scope()).isEqualTo(toScopeString(scope));
        assertThat(capsule.scopeSet()).contains(scope);
        assertThat(capsule.scopeSet()).doesNotContain("foo");
        assertThat(capsule.scopeSet()).containsExactly(scope);
        assertThat(capsule.extras()).isEqualTo(extras);
        assertThat(capsule.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testOfRawResponse1() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String rawResponse =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";

        final AccessTokenCapsule capsule = AccessTokenCapsule.of(rawResponse, null);

        Thread.sleep(100);

        assertThat(capsule.accessToken()).isEqualTo(accessToken);
        assertThat(capsule.tokenType()).isEqualTo(tokenType);
        assertThat(capsule.issuedAt()).isBefore(Instant.now());
        assertThat(capsule.expiresIn()).isEqualTo(expiresIn);
        assertThat(capsule.expiresAt()).isEqualTo(capsule.issuedAt().plus(expiresIn));
        assertThat(capsule.isValid()).isTrue();
        assertThat(capsule.isValid(capsule.issuedAt().plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(capsule.authorization()).isEqualTo("Bearer " + accessToken);
        assertThat(capsule.refreshToken()).isEqualTo(refreshToken);
        assertThat(capsule.scope()).isEqualTo(toScopeString(scope));
        assertThat(capsule.scopeSet()).contains(scope);
        assertThat(capsule.scopeSet()).doesNotContain("foo");
        assertThat(capsule.scopeSet()).containsExactly(scope);
        assertThat(capsule.extras()).isEqualTo(extras);
        assertThat(capsule.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testOfRawResponse2() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String rawResponse =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"example_parameter\":\"example_value\"}";

        final AccessTokenCapsule capsule = AccessTokenCapsule.of(rawResponse, "read write");

        Thread.sleep(100);

        assertThat(capsule.accessToken()).isEqualTo(accessToken);
        assertThat(capsule.tokenType()).isEqualTo(tokenType);
        assertThat(capsule.issuedAt()).isBefore(Instant.now());
        assertThat(capsule.expiresIn()).isEqualTo(expiresIn);
        assertThat(capsule.expiresAt()).isEqualTo(capsule.issuedAt().plus(expiresIn));
        assertThat(capsule.isValid()).isTrue();
        assertThat(capsule.isValid(capsule.issuedAt().plus(Duration.ofSeconds(3000L)))).isTrue();
        assertThat(capsule.authorization()).isEqualTo("Bearer " + accessToken);
        assertThat(capsule.refreshToken()).isEqualTo(refreshToken);
        assertThat(capsule.scope()).isEqualTo(toScopeString(scope));
        assertThat(capsule.scopeSet()).contains(scope);
        assertThat(capsule.scopeSet()).doesNotContain("foo");
        assertThat(capsule.scopeSet()).containsExactly(scope);
        assertThat(capsule.extras()).isEqualTo(extras);
        assertThat(capsule.rawResponse()).isEqualTo(rawResponse);
    }

    @Test
    void testToString() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant issuedAt = Instant.parse("2010-01-01T10:15:30Z");
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");
        final String toString =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"issued_at\":\"2010-01-01T10:15:30Z\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";

        final AccessTokenCapsule capsule = AccessTokenCapsule.builder(accessToken)
                                                             .tokenType(tokenType)
                                                             .issuedAt(issuedAt)
                                                             .expiresIn(expiresIn)
                                                             .refreshToken(refreshToken)
                                                             .extras(extras)
                                                             .scope(scope)
                                                             .build();

        System.out.println(capsule);
        assertThat(capsule.toString()).isEqualTo(toString);
    }

    @Test
    void testScope() throws Exception {
        final String[] scope = { "read", "write" };

        final String rawResponse1 =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";
        final AccessTokenCapsule capsule1 = AccessTokenCapsule.of(rawResponse1, null);
        assertThat(capsule1.scope()).isEqualTo(toScopeString(scope));
        assertThat(capsule1.scopeSet()).containsExactly(scope);

        final String rawResponse2 =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"example_parameter\":\"example_value\"}";
        final AccessTokenCapsule capsule2 = AccessTokenCapsule.of(rawResponse2, "read write");
        assertThat(capsule2.scope()).isEqualTo(toScopeString(scope));
        assertThat(capsule2.scopeSet()).containsExactly(scope);

        final String rawResponse3 =
                "{\"access_token\":\"2YotnFZFEjr1zCsicMWpAA\"," +
                "\"token_type\":\"bearer\"," +
                "\"expires_in\":3600," +
                "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"," +
                "\"scope\":\"read write\"," +
                "\"example_parameter\":\"example_value\"}";
        final AccessTokenCapsule capsule3 = AccessTokenCapsule.of(rawResponse3, "foo");
        assertThat(capsule3.scope()).isEqualTo(toScopeString(scope));
        assertThat(capsule3.scopeSet()).containsExactly(scope);
    }

    @Test
    void testEquals() throws Exception {
        final String accessToken = "2YotnFZFEjr1zCsicMWpAA";
        final String tokenType = "bearer";
        final Duration expiresIn = Duration.ofSeconds(3600L);
        final Instant issuedAt = Instant.parse("2010-01-01T10:15:30Z");
        final String refreshToken = "tGzv3JOkF0XG5Qx2TlKWIA";
        final String[] scope = { "read", "write" };
        final Map<String, String> extras = Collections.singletonMap("example_parameter", "example_value");

        final AccessTokenCapsule capsule1 = AccessTokenCapsule.builder(accessToken)
                                                              .tokenType(tokenType)
                                                              .issuedAt(issuedAt)
                                                              .expiresIn(expiresIn)
                                                              .refreshToken(refreshToken)
                                                              .extras(extras)
                                                              .scope(scope)
                                                              .build();

        final AccessTokenCapsule capsule2 = AccessTokenCapsule.builder(accessToken)
                                                              .tokenType(tokenType)
                                                              .issuedAt(issuedAt)
                                                              .expiresIn(expiresIn)
                                                              .refreshToken(refreshToken)
                                                              .extras(extras)
                                                              .scope(scope)
                                                              .build();

        assertThat(capsule2).isEqualTo(capsule1);

        final AccessTokenCapsule capsule3 = AccessTokenCapsule.builder(accessToken)
                                                              .tokenType(tokenType)
                                                              .issuedAt(issuedAt)
                                                              .expiresIn(expiresIn)
                                                              .refreshToken(refreshToken)
                                                              .extras(extras)
                                                              .scope("read")
                                                              .build();

        assertThat(capsule3).isNotEqualTo(capsule1);

        final AccessTokenCapsule capsule4 = AccessTokenCapsule.builder(accessToken)
                                                              .tokenType(tokenType)
                                                              .issuedAt(Instant.now())
                                                              .expiresIn(expiresIn)
                                                              .refreshToken(refreshToken)
                                                              .extras(extras)
                                                              .scope(scope)
                                                              .build();

        assertThat(capsule4).isEqualTo(capsule1);
    }

    @Nullable
    private static String toScopeString(String... scopes) {
        if (scopes.length == 0) {
            return null;
        }
        return String.join(SCOPE_SEPARATOR, scopes);
    }
}
