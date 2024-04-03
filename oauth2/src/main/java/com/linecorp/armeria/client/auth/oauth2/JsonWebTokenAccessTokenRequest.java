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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.JWT_ASSERTION;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.JWT_GRANT_TYPE;

import java.util.List;

import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

/**
 * An access token request that uses a JSON Web Token (JWT) as an authorization grant, as per
 * <a href="https://datatracker.ietf.org/doc/html/rfc7523#section-2.1">[RFC7523], Section 2.1</a>.
 */
final class JsonWebTokenAccessTokenRequest extends AbstractAccessTokenRequest {

    private final String jsonWebToken;

    JsonWebTokenAccessTokenRequest(String jsonWebToken, @Nullable ClientAuthentication clientAuthentication,
                                   @Nullable List<String> scopes) {
        super(clientAuthentication, JWT_GRANT_TYPE, scopes);
        this.jsonWebToken = jsonWebToken;
    }

    @Override
    void doAddBodyParams0(QueryParamsBuilder formBuilder) {
        formBuilder.add(JWT_ASSERTION, jsonWebToken);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonWebTokenAccessTokenRequest)) {
            return false;
        }
        final JsonWebTokenAccessTokenRequest that = (JsonWebTokenAccessTokenRequest) o;
        return super.equals(that) && jsonWebToken.equals(that.jsonWebToken);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + jsonWebToken.hashCode();
    }

    @Override
    public String toString() {
        return toStringHelper()
                .add("jsonWebToken", "****")
                .toString();
    }
}
