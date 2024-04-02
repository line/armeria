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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REFRESH_TOKEN;

import java.util.List;
import java.util.Objects;

import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

/**
 * A request to refresh an access token using a refresh token as per
 * <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-6">RFC 6749, Section 6</a>.
 */
final class RefreshAccessTokenRequest extends AbstractAccessTokenRequest {

    private final String refreshToken;

    RefreshAccessTokenRequest(@Nullable ClientAuthentication clientAuthentication,
                              String refreshToken, @Nullable List<String> scopes) {
        super(clientAuthentication, REFRESH_TOKEN, scopes);
        this.refreshToken = refreshToken;
    }

    @Override
    void doAddBodyParams0(QueryParamsBuilder formBuilder) {
        formBuilder.add(REFRESH_TOKEN, refreshToken);
    }

    // implements equals, hashcode and toString
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof RefreshAccessTokenRequest)) {
            return false;
        }
        final RefreshAccessTokenRequest other = (RefreshAccessTokenRequest) o;
        return super.equals(o) && Objects.equals(refreshToken, other.refreshToken);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + refreshToken.hashCode();
    }

    @Override
    public String toString() {
        return toStringHelper()
                .add("refreshToken", "****")
                .toString();
    }
}
