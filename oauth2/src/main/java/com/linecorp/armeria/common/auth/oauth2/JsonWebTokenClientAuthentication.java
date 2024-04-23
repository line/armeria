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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_ASSERTION;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_ASSERTION_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_ASSERTION_TYPE_JWT;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.QueryParamsBuilder;

/**
 * A client authentication method that uses a JSON Web Token (JWT) as the client assertion, as per
 * <a href="https://datatracker.ietf.org/doc/html/rfc7523#section-2.2">[RFC7523], Section 2.2</a>.
 */
final class JsonWebTokenClientAuthentication implements ClientAuthentication {

    private final String jsonWebToken;

    JsonWebTokenClientAuthentication(String jsonWebToken) {
        this.jsonWebToken = jsonWebToken;
    }

    @Override
    public void addAsHeaders(HttpHeadersBuilder headersBuilder) {}

    @Override
    public void addAsBodyParams(QueryParamsBuilder formBuilder) {
        formBuilder.add(CLIENT_ASSERTION_TYPE, CLIENT_ASSERTION_TYPE_JWT);
        formBuilder.add(CLIENT_ASSERTION, jsonWebToken);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("jsonWebToken", "****")
                          .toString();
    }
}
