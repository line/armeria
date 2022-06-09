/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.conditional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An Entity Tag as used in <a href="https://datatracker.ietf.org/doc/html/rfc2732">RFC 7232</a>.
 */
public final class ETag {
    public static final List<ETag> ASTERISK_ETAG =
            ImmutableList.of(
                    new ETag("*", false)
            );

    private static final Pattern ETAG_REGEX = Pattern.compile("(W/|)\"([^\"]*)\"");

    private final String eTag;
    private final boolean weak;

    /**
     * Create an Entity tag instance.
     * @param eTag The data.
     * @param weak The weak state.
     */
    public ETag(String eTag, boolean weak) {
        Preconditions.checkArgument(validateEtag(eTag), "Invalid ETag: '%s'", eTag);
        this.eTag = eTag;
        this.weak = weak;
    }

    @Override
    public String toString() {
        return asHeaderValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ETag)) {
            return false;
        }
        final ETag eTag = (ETag) o;
        return weak == eTag.weak && Objects.equal(this.eTag, eTag.eTag);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(eTag, weak);
    }

    /**
     * Getter.
     * @return the Entity Tag.
     */
    public String getETag() {
        return eTag;
    }

    /**
     * Getter.
     * @return Weak state.
     */
    public boolean isWeak() {
        return weak;
    }

    /**
     * Getter.
     * @return The inverse of Weak state.
     */
    public boolean isStrong() {
        return !weak;
    }

    /**
     * Convert into header value.
     * @return Make a string suitable for debugging and adding to headers.
     */
    public String asHeaderValue() {
        final StringBuilder builder = new StringBuilder();
        if (weak) {
            builder.append("W/");
        }
        return builder
                .append('\"')
                .append(eTag)
                .append('\"')
                .toString();
    }

    /**
     * Validate that the Entity Tag does not contain any illegal characters.
     * @param etag the Entity Tag.
     * @return true if it validates clean.
     */
    public static boolean validateEtag(String etag) {
        for (int i = 0; i < etag.length(); i++) {
            final char value = etag.charAt(i);
            // 0x21, 0x23-0x74 and 0x7f-0xff in US-ASCII is allowed.
            // That means it's easier to check for what's _disallowed_ as
            // all unicode characters are allowed.
            if (value <= 0x20 || value == 0x22 || value == 0x7f) {
                // Parsing error - throw Exception instead?
                return false;
            }
        }
        return true;
    }

    /**
     * Splits the If-Not-Modified header into multiple Entity Tags.
     * Format is according to RFC2732
     * <pre>
     *  If-Match = "*" / 1#entity-tag
     *  If-None-Match = "*" / 1#entity-tag
     *      entity-tag = [ weak ] opaque-tag
     *      weak       = %x57.2F ; "W/", case-sensitive
     *      opaque-tag = DQUOTE *etagc DQUOTE
     *      etagc      = %x21 / %x23-7E / obs-text
     *              ; VCHAR except double quotes, plus obs-text
     * </pre>
     * @param header the If-Not-Modified or If-Match header of the request
     * @return list of entity tags or null on parsing error
     */
    @Nullable
    public static List<ETag> parseHeader(@Nullable String header) {
        if (header == null) {
            return ImmutableList.of();
        }
        if ("*".equals(header)) {
            return ETag.ASTERISK_ETAG;
        }

        final Builder<ETag> results = ImmutableList.builder();
        final Matcher matcher = ETAG_REGEX.matcher(header);
        int expectedPos = 0;
        while (matcher.find()) {
            final String divider = header.substring(expectedPos, matcher.start()).trim();
            if (!divider.isEmpty() && !",".equals(divider)) {
                // Parsing error - throw Exception instead?
                return null;
            }
            final boolean weak = "W/".equals(matcher.group(1));
            results.add(new ETag(matcher.group(2), weak));
            expectedPos = matcher.end();
        }
        final String trailer = header.substring(expectedPos).trim();
        if (!trailer.isEmpty()) {
            // Parsing error - throw Exception instead?
            return null;
        }
        return results.build();
    }
}
