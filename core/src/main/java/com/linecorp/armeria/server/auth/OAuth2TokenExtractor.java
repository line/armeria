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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

/**
 * Extracts {@link OAuth2Token} from {@link HttpHeaders}, in order to be used by {@link HttpAuthServiceBuilder}.
 */
final class OAuth2TokenExtractor implements Function<HttpHeaders, OAuth2Token> {

    private static final Logger logger = LoggerFactory.getLogger(OAuth2TokenExtractor.class);
    private static final Pattern AUTHORIZATION_HEADER_PATTERN = Pattern.compile(
            "\\s*(?i)bearer\\s+(?<accessToken>\\S+)\\s*");

    @Override
    public OAuth2Token apply(HttpHeaders headers) {
        String authorization = headers.get(HttpHeaderNames.AUTHORIZATION);
        if (Strings.isNullOrEmpty(authorization)) {
            return null;
        }

        Matcher matcher = AUTHORIZATION_HEADER_PATTERN.matcher(authorization);
        if (!matcher.matches()) {
            logger.warn("Invalid authorization header: " + authorization);
            return null;
        }

        return OAuth2Token.of(matcher.group("accessToken"));
    }
}
