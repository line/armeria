/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.HttpHeaderNames;

/**
 * The authorization token in {@link HttpHeaderNames#AUTHORIZATION} header.
 */
public abstract class AuthToken {
    /**
     * Create a bearer token of
     * <a href="https://en.wikipedia.org/wiki/Basic_access_authentication">HTTP basic access authentication</a>.
     */
    public static BasicToken ofBasic(String username, String password) {
        return new BasicToken(username, password);
    }

    /**
     * Create a new {@link OAuth1aTokenBuilder}.
     */
    public static OAuth1aTokenBuilder builderForOAuth1a() {
        return new OAuth1aTokenBuilder();
    }

    /**
     * Create a bearer token of
     * <a href="https://datatracker.ietf.org/doc/rfc6750/">OAuth 2.0 authentication</a>.
     */
    public static OAuth2Token ofOAuth2(String token) {
        return new OAuth2Token(token);
    }

    AuthToken() {}

    /**
     * Returns the string that is sent as the value of the {@link HttpHeaderNames#AUTHORIZATION} header.
     */
    public abstract String asHeaderValue();
}
