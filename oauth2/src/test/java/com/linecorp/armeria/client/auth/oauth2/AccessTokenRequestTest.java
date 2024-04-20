/*
 * Copyright 2024 LINE Corporation
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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

class AccessTokenRequestTest {
    //test_client:client_secret
    private static final String BASIC_CLIENT_CREDENTIALS = "dGVzdF9jbGllbnQ6Y2xpZW50X3NlY3JldA==";

    @ValueSource(strings = { "", "read" })
    @ParameterizedTest
    void clientCredentials(String scope) {
        final AccessTokenRequest accessTokenRequest;
        if (!Strings.isNullOrEmpty(scope)) {
            final List<String> scopes = ImmutableList.of(scope);
            final ClientAuthentication clientAuthentication =
                    ClientAuthentication.ofClientPassword("test_client", "client_secret");
            accessTokenRequest = AccessTokenRequest.ofClientCredentials(clientAuthentication, scopes);
        } else {
            accessTokenRequest = AccessTokenRequest.ofClientCredentials("test_client", "client_secret");
        }
        final ClientAuthentication clientAuthentication = accessTokenRequest.clientAuthentication();
        assertThat(clientAuthentication).isNotNull();
        assertThat(clientAuthentication.asHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic " + BASIC_CLIENT_CREDENTIALS);
        assertThat(clientAuthentication).isEqualTo(
                ClientAuthentication.ofClientPassword("test_client", "client_secret"));
        final AggregatedHttpRequest request =
                accessTokenRequest.asHttpRequest("/token").aggregate().join();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.headers().contentType()).isEqualTo(MediaType.FORM_DATA);
        assertThat(request.path()).isEqualTo("/token");
        final String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        assertThat(authorization).isEqualTo("Basic " + BASIC_CLIENT_CREDENTIALS);
        if (!Strings.isNullOrEmpty(scope)) {
            assertThat(request.contentUtf8()).isEqualTo("grant_type=client_credentials&scope=" + scope);
        } else {
            assertThat(request.contentUtf8()).isEqualTo("grant_type=client_credentials");
        }
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void resourceUserPassword(boolean useClientAuthentication) {
        final AccessTokenRequest accessTokenRequest;
        if (useClientAuthentication) {
            final ClientAuthentication clientAuthentication =
                    ClientAuthentication.ofClientPassword("test_client", "client_secret");
            accessTokenRequest =
                    AccessTokenRequest.ofResourceOwnerPassword("test_user", "user_password",
                                                               clientAuthentication);
            assertThat(accessTokenRequest.clientAuthentication()).isNotNull();
            assertThat(accessTokenRequest.clientAuthentication().asHeaders().get(HttpHeaderNames.AUTHORIZATION))
                    .isEqualTo("Basic " + BASIC_CLIENT_CREDENTIALS);
            assertThat(accessTokenRequest.clientAuthentication())
                    .isEqualTo(clientAuthentication);
        } else {
            accessTokenRequest =
                    AccessTokenRequest.ofResourceOwnerPassword("test_user", "user_password");
            final ClientAuthentication clientAuthentication = accessTokenRequest.clientAuthentication();
            assertThat(clientAuthentication).isNull();
        }
        assertThat(accessTokenRequest.bodyParams().get("grant_type")).isEqualTo("password");
        assertThat(accessTokenRequest.bodyParams().get("username")).isEqualTo("test_user");
        assertThat(accessTokenRequest.bodyParams().get("password")).isEqualTo("user_password");

        final AggregatedHttpRequest request =
                accessTokenRequest.asHttpRequest("/token/user").aggregate().join();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.headers().contentType()).isEqualTo(MediaType.FORM_DATA);
        assertThat(request.path()).isEqualTo("/token/user");
        assertThat(request.contentUtf8()).isEqualTo(
                "grant_type=password&username=test_user&password=user_password");
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void jsonWebToken(boolean useClientAuthentication) {
        final AccessTokenRequest accessTokenRequest;
        if (useClientAuthentication) {
            final ClientAuthentication clientAuthentication =
                    ClientAuthentication.ofClientPassword("test_client", "client_secret");
            accessTokenRequest = AccessTokenRequest.ofJsonWebToken("test_jwt", clientAuthentication);
            assertThat(accessTokenRequest.clientAuthentication()).isNotNull();
            assertThat(accessTokenRequest.clientAuthentication().asHeaders().get(HttpHeaderNames.AUTHORIZATION))
                    .isEqualTo("Basic " + BASIC_CLIENT_CREDENTIALS);
            assertThat(accessTokenRequest.clientAuthentication())
                    .isEqualTo(clientAuthentication);
        } else {
            accessTokenRequest = AccessTokenRequest.ofJsonWebToken("test_jwt");
            final ClientAuthentication clientAuthentication = accessTokenRequest.clientAuthentication();
            assertThat(clientAuthentication).isNull();
        }
        assertThat(accessTokenRequest.bodyParams().get("grant_type"))
                .isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
        assertThat(accessTokenRequest.bodyParams().get("assertion")).isEqualTo("test_jwt");

        final AggregatedHttpRequest request =
                accessTokenRequest.asHttpRequest("/token/jwt").aggregate().join();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.headers().contentType()).isEqualTo(MediaType.FORM_DATA);
        assertThat(request.path()).isEqualTo("/token/jwt");
        assertThat(request.contentUtf8()).isEqualTo(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=test_jwt");
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testBodyParams(boolean useFormBuilder) {
        final AccessTokenRequest accessTokenRequest =
                AccessTokenRequest.ofClientCredentials(
                        ClientAuthentication.ofClientPassword("test_client", "client_password", false));
        final QueryParams bodyParams;
        if (useFormBuilder) {
            final QueryParamsBuilder formBuilder = QueryParams.builder();
            accessTokenRequest.addBodyParams(formBuilder);
            bodyParams = formBuilder.build();
        } else {
            bodyParams = accessTokenRequest.bodyParams();
        }
        assertThat(bodyParams.get("grant_type")).isEqualTo("client_credentials");
        assertThat(bodyParams.get("client_id")).isEqualTo("test_client");
        assertThat(bodyParams.get("client_secret")).isEqualTo("client_password");
        final AggregatedHttpRequest req = accessTokenRequest.asHttpRequest("/token")
                                                            .aggregate().join();
        final QueryParams bodyParams1 = QueryParams.fromQueryString(req.contentUtf8());
        assertThat(bodyParams1.get("grant_type")).isEqualTo("client_credentials");
        assertThat(bodyParams1.get("client_id")).isEqualTo("test_client");
        assertThat(bodyParams1.get("client_secret")).isEqualTo("client_password");
    }

    @Test
    void invalidScope() {
        assertThatThrownBy(() -> {
            AccessTokenRequest.ofResourceOwnerPassword(
                    "test_user", "user_password", null,
                    ImmutableList.of("invalid\""));
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid scope token: invalid\"");

        assertThatThrownBy(() -> {
            AccessTokenRequest.ofResourceOwnerPassword(
                    "test_user", "user_password", null,
                    ImmutableList.of("\\invalid"));
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid scope token: \\invalid");

        AccessTokenRequest.ofResourceOwnerPassword(
                "test_user", "user_password", null,
                ImmutableList.of("valid"));
    }
}
