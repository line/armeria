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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.RequestHeaders;

/**
 * A utility class that provides singleton instances of authorization token extractor functions.
 */
public final class AuthTokenExtractors {

    /**
     * A {@link BasicToken} extractor function.
     *
     * @deprecated Use {@link #basic()}.
     */
    @Deprecated
    public static final Function<RequestHeaders, BasicToken> BASIC =
            new BasicTokenExtractor(HttpHeaderNames.AUTHORIZATION);

    /**
     * An {@link OAuth1aToken} extractor function.
     *
     * @deprecated Use {@link #oAuth1a()}.
     */
    @Deprecated
    public static final Function<RequestHeaders, OAuth1aToken> OAUTH1A =
            new OAuth1aTokenExtractor(HttpHeaderNames.AUTHORIZATION);

    /**
     * An {@link OAuth2Token} extractor function.
     *
     * @deprecated Use {@link #oAuth2()}.
     */
    @Deprecated
    public static final Function<RequestHeaders, OAuth2Token> OAUTH2 =
            new OAuth2TokenExtractor(HttpHeaderNames.AUTHORIZATION);

    /**
     * Returns a {@link BasicToken} extractor function.
     */
    public static Function<RequestHeaders, BasicToken> basic() {
        return BASIC;
    }

    /**
     * Returns an {@link OAuth1aToken} extractor function.
     */
    public static Function<RequestHeaders, OAuth1aToken> oAuth1a() {
        return OAUTH1A;
    }

    /**
     * Returns an {@link OAuth2Token} extractor function.
     */
    public static Function<RequestHeaders, OAuth2Token> oAuth2() {
        return OAUTH2;
    }

    private AuthTokenExtractors() {}
}
