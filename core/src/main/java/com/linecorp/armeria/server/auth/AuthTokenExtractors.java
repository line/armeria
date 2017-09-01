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

import com.linecorp.armeria.common.HttpHeaders;

/**
 * A utility class that provides singleton instances of authorization token extractor functions.
 */
public final class AuthTokenExtractors {

    /**
     * A {@link BasicToken} extractor function instance.
     */
    public static final Function<HttpHeaders, BasicToken> BASIC = new BasicTokenExtractor();

    /**
     * An {@link OAuth1aToken} extractor function instance.
     */
    public static final Function<HttpHeaders, OAuth1aToken> OAUTH1A = new OAuth1aTokenExtractor();

    /**
     * An {@link OAuth2Token} extractor function instance.
     */
    public static final Function<HttpHeaders, OAuth2Token> OAUTH2 = new OAuth2TokenExtractor();

    private AuthTokenExtractors() {}
}
