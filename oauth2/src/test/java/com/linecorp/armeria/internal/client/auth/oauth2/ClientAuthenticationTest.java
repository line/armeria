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

package com.linecorp.armeria.internal.client.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_ID;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

class ClientAuthenticationTest {

    @Test
    void basicAuth() {
        final ClientAuthentication clientAuthentication = ClientAuthentication.ofBasic("foo");
        assertThat(clientAuthentication.asHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic foo");
        assertThat(clientAuthentication.asBodyParams()).isEmpty();
    }

    @Test
    void clientPasswordAsBasicAuth() {
        final ClientAuthentication clientAuthentication = ClientAuthentication.ofClientPassword("foo", "bar");
        assertThat(clientAuthentication.asHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo(AuthToken.ofBasic("foo", "bar").asHeaderValue());
        assertThat(clientAuthentication.asBodyParams()).isEmpty();
    }

    @Test
    void clientPasswordAsBodyParams() {
        final ClientAuthentication clientAuthentication = ClientAuthentication.ofClientPassword("foo", "bar",
                                                                                                false);
        assertThat(clientAuthentication.asHeaders()).isEmpty();
        final QueryParams bodyParameters = clientAuthentication.asBodyParams();
        assertThat(bodyParameters.get(CLIENT_ID)).isEqualTo("foo");
        assertThat(bodyParameters.get(CLIENT_SECRET)).isEqualTo("bar");
    }

    @Test
    void anyAuthorization() {
        final ClientAuthentication clientAuthentication = ClientAuthentication.ofAuthorization("Bearer", "foo");
        assertThat(clientAuthentication.asHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Bearer foo");
        assertThat(clientAuthentication.asBodyParams()).isEmpty();
    }

    @Test
    void jsonWebToken() {
        final ClientAuthentication clientAuthentication = ClientAuthentication.ofJsonWebToken("foo");
        assertThat(clientAuthentication.asHeaders()).isEmpty();
        final QueryParams bodyParameters = clientAuthentication.asBodyParams();
        assertThat(bodyParameters.get("client_assertion")).isEqualTo("foo");
        assertThat(bodyParameters.get("client_assertion_type"))
                .isEqualTo("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
    }
}
