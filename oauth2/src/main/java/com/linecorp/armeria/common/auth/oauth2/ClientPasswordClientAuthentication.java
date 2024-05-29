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

package com.linecorp.armeria.common.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_ID;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_SECRET;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;

final class ClientPasswordClientAuthentication implements ClientAuthentication {

    private final String clientId;
    private final String clientSecret;
    private final boolean useBasicAuth;

    @Nullable
    private String headerValue;

    ClientPasswordClientAuthentication(String clientId, String clientSecret, boolean useBasicAuth) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.useBasicAuth = useBasicAuth;
    }

    @Override
    public void addAsHeaders(HttpHeadersBuilder headersBuilder) {
        if (!useBasicAuth) {
            return;
        }
        if (headerValue == null) {
            headerValue = AuthToken.ofBasic(clientId, clientSecret).asHeaderValue();
        }
        headersBuilder.add(HttpHeaderNames.AUTHORIZATION, headerValue);
    }

    @Override
    public void addAsBodyParams(QueryParamsBuilder formBuilder) {
        if (useBasicAuth) {
            return;
        }

        formBuilder.add(CLIENT_ID, clientId);
        formBuilder.add(CLIENT_SECRET, clientSecret);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientPasswordClientAuthentication)) {
            return false;
        }
        final ClientPasswordClientAuthentication that = (ClientPasswordClientAuthentication) o;
        return useBasicAuth == that.useBasicAuth && clientId.equals(that.clientId) &&
               clientSecret.equals(that.clientSecret);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId, clientSecret, useBasicAuth);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("useBasicAuth", useBasicAuth)
                          .add("clientId", clientId)
                          .add("clientSecret", "****")
                          .toString();
    }
}
