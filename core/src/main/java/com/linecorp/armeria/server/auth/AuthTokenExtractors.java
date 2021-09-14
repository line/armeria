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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * A utility class that provides singleton instances of authorization token extractor functions.
 */
public final class AuthTokenExtractors {

    private static final Function<? super RequestHeaders, @Nullable BasicToken> BASIC =
            new BasicTokenExtractor(HttpHeaderNames.AUTHORIZATION);

    private static final Function<? super RequestHeaders, @Nullable OAuth1aToken> OAUTH1A =
            new OAuth1aTokenExtractor(HttpHeaderNames.AUTHORIZATION);

    private static final Function<? super RequestHeaders, @Nullable OAuth2Token> OAUTH2 =
            new OAuth2TokenExtractor(HttpHeaderNames.AUTHORIZATION);

    /**
     * Returns a {@link BasicToken} extractor function.
     */
    public static Function<? super RequestHeaders, @Nullable BasicToken> basic() {
        return BASIC;
    }

    /**
     * Returns an {@link OAuth1aToken} extractor function.
     */
    public static Function<? super RequestHeaders, @Nullable OAuth1aToken> oAuth1a() {
        return OAUTH1A;
    }

    /**
     * Returns an {@link OAuth2Token} extractor function.
     */
    public static Function<? super RequestHeaders, @Nullable OAuth2Token> oAuth2() {
        return OAUTH2;
    }

    private AuthTokenExtractors() {}
}
