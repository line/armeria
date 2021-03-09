/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.auth;

import static com.linecorp.armeria.common.auth.AuthUtil.secureEquals;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;

/**
 * The bearer token of <a href="https://datatracker.ietf.org/doc/rfc6750/">OAuth 2.0 authentication</a>.
 */
public final class OAuth2Token {

    /**
     * Creates a new {@link OAuth2Token} from the given {@code accessToken}.
     */
    public static OAuth2Token of(String accessToken) {
        return new OAuth2Token(accessToken);
    }

    private final String accessToken;

    private OAuth2Token(String accessToken) {
        this.accessToken = requireNonNull(accessToken, "accessToken");
    }

    /**
     * Returns the access token.
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * Returns the string that is sent as the value of the {@link HttpHeaderNames#AUTHORIZATION} header.
     */
    public String asHeaderValue() {
        return "Bearer " + accessToken;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OAuth2Token)) {
            return false;
        }
        final OAuth2Token that = (OAuth2Token) o;
        return secureEquals(accessToken, that.accessToken);
    }

    @Override
    public int hashCode() {
        return accessToken.hashCode();
    }

    @Override
    public String toString() {
        return "OAuth2Token(****)";
    }
}
