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

package com.linecorp.armeria.server.auth;

import static java.util.Objects.requireNonNull;

/**
 * The bearer token of <a href="https://tools.ietf.org/html/rfc6750">OAuth 2.0 authentication</a>.
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

    public String accessToken() {
        return accessToken;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OAuth2Token that = (OAuth2Token) o;
        return accessToken.equals(that.accessToken);
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
