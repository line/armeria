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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

/**
 * Extracts {@link BasicToken} from {@link HttpHeaders}, in order to be used by {@link HttpAuthServiceBuilder}.
 */
final class BasicTokenExtractor implements Function<HttpHeaders, BasicToken> {

    private static final Logger logger = LoggerFactory.getLogger(BasicTokenExtractor.class);

    private static final Pattern AUTHORIZATION_HEADER_PATTERN = Pattern.compile(
            "\\s*(?i)basic\\s+(?<encoded>\\S+)\\s*");
    private static final Decoder BASE64_DECODER = Base64.getDecoder();

    @Override
    public BasicToken apply(HttpHeaders headers) {
        String authorization = headers.get(HttpHeaderNames.AUTHORIZATION);
        if (Strings.isNullOrEmpty(authorization)) {
            return null;
        }

        Matcher matcher = AUTHORIZATION_HEADER_PATTERN.matcher(authorization);
        if (!matcher.matches()) {
            logger.warn("Invalid authorization header: {}", authorization);
            return null;
        }

        String base64 = matcher.group("encoded");
        byte[] decoded;
        try {
            decoded = BASE64_DECODER.decode(base64);
        } catch (IllegalArgumentException e) {
            logger.warn("Base64 decoding failed: {}", base64);
            return null;
        }

        String credential = new String(decoded, StandardCharsets.UTF_8);
        int sep = credential.indexOf(':');
        if (sep == -1) {
            logger.warn("Invalid credential: {}", credential);
            return null;
        }
        String username = credential.substring(0, sep);
        String password = credential.substring(sep + 1);

        return BasicToken.of(username, password);
    }
}
