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
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.BasicToken;

import io.netty.util.AsciiString;

/**
 * Extracts {@link BasicToken} from {@link RequestHeaders}, in order to be used by
 * {@link AuthServiceBuilder}.
 */
final class BasicTokenExtractor implements Function<RequestHeaders, BasicToken> {

    private static final Logger logger = LoggerFactory.getLogger(BasicTokenExtractor.class);

    private static final Pattern AUTHORIZATION_HEADER_PATTERN = Pattern.compile(
            "\\s*(?i)basic\\s+(?<encoded>\\S+)\\s*");
    private static final Decoder BASE64_DECODER = Base64.getDecoder();

    private final AsciiString header;

    BasicTokenExtractor(CharSequence header) {
        this.header = HttpHeaderNames.of(header);
    }

    @Nullable
    @Override
    public BasicToken apply(RequestHeaders headers) {
        @Nullable
        final String authorization = requireNonNull(headers, "headers").get(header);
        if (Strings.isNullOrEmpty(authorization)) {
            return null;
        }

        final Matcher matcher = AUTHORIZATION_HEADER_PATTERN.matcher(authorization);
        if (!matcher.matches()) {
            logger.warn("Invalid authorization header: {}", authorization);
            return null;
        }

        final String base64 = matcher.group("encoded");
        final byte[] decoded;
        try {
            decoded = BASE64_DECODER.decode(base64);
        } catch (IllegalArgumentException e) {
            // No need to log stack trace for this because the reason is so obvious.
            logger.warn("Base64 decoding failed: {}", base64);
            return null;
        }

        final String credential = new String(decoded, StandardCharsets.UTF_8);
        final int sep = credential.indexOf(':');
        if (sep == -1) {
            logger.warn("Invalid credential: {}", credential);
            return null;
        }
        final String username = credential.substring(0, sep);
        final String password = credential.substring(sep + 1);

        return BasicToken.of(username, password);
    }
}
