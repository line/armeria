/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.BitSet;

import javax.annotation.Nullable;

/**
 * Builds a {@link Cookie}.
 */
public final class CookieBuilder {

    private static final BitSet VALID_COOKIE_ATTRIBUTE_VALUE_OCTETS = validCookieAttributeValueOctets();

    // path-value        = <any CHAR except CTLs or ";">
    private static BitSet validCookieAttributeValueOctets() {
        final BitSet bits = new BitSet();
        for (int i = 32; i < 127; i++) {
            bits.set(i);
        }
        bits.set(';', false);
        return bits;
    }

    private static int firstInvalidOctet(CharSequence cs, BitSet bits) {
        for (int i = 0; i < cs.length(); i++) {
            final char c = cs.charAt(i);
            if (!bits.get(c)) {
                return i;
            }
        }
        return -1;
    }

    private static String validateAttributeValue(String name, @Nullable String value) {
        value = requireNonNull(value, name).trim();
        checkArgument(!value.isEmpty(), "%s is empty.", name);
        final int i = firstInvalidOctet(value, VALID_COOKIE_ATTRIBUTE_VALUE_OCTETS);
        checkArgument(i == -1,
                      "%s contains a prohibited character: %s", name, value);
        return value;
    }

    private final String name;
    private final String value;
    private boolean valueQuoted;
    @Nullable
    private String domain;
    @Nullable
    private String path;
    long maxAge = Cookie.UNDEFINED_MAX_AGE;
    private boolean secure;
    private boolean httpOnly;
    @Nullable
    private String sameSite;

    // These two fields are only used by ClientCookieDecoder.
    int expiresStart;
    int expiresEnd;

    CookieBuilder(String name, String value) {
        this.name = requireNonNull(name, "name");
        this.value = requireNonNull(value, "value");
    }

    /**
     * Sets whether the value of the {@link Cookie} needs to be wrapped with double quotes when encoding.
     * If unspecified, the {@link Cookie} will not be wrapped with double quotes.
     */
    public CookieBuilder valueQuoted(boolean valueQuoted) {
        this.valueQuoted = valueQuoted;
        return this;
    }

    /**
     * Sets the domain of the {@link Cookie}.
     */
    public CookieBuilder domain(String domain) {
        this.domain = validateAttributeValue("domain", requireNonNull(domain, "domain"));
        return this;
    }

    /**
     * Sets the path of the {@link Cookie}.
     */
    public CookieBuilder path(String path) {
        this.path = validateAttributeValue("path", requireNonNull(path, "path"));
        return this;
    }

    /**
     * Sets the maximum age of the {@link Cookie} in seconds. If an age of {@code 0} is specified,
     * the {@link Cookie} will be automatically removed by browser because it will expire immediately.
     * If {@link Cookie#UNDEFINED_MAX_AGE} is specified, this {@link Cookie} will be removed when the
     * browser is closed. If unspecified, {@link Cookie#UNDEFINED_MAX_AGE} will be used.
     *
     * @param maxAge The maximum age of this {@link Cookie} in seconds
     */
    public CookieBuilder maxAge(long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * Sets the security status of the {@link Cookie}. If unspecified, {@code false} will be used.
     */
    public CookieBuilder secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets whether the {@link Cookie} is HTTP only. If {@code true}, the {@link Cookie} cannot be accessed
     * by a client side script. However, this works only if the browser supports it. For for information,
     * please look <a href="http://www.owasp.org/index.php/HTTPOnly">here</a>. If unspecified, {@code false}
     * will be used.
     */
    public CookieBuilder httpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    /**
     * Sets the <a href="https://tools.ietf.org/html/draft-ietf-httpbis-rfc6265bis-03#section-4.1.2.7"
     * >{@code SameSite}</a> attribute of the {@link Cookie}. The value is supposed to be one of {@code "Lax"},
     * {@code "Strict"} or {@code "None"}. Note that this attribute is server-side only.
     */
    public CookieBuilder sameSite(String sameSite) {
        this.sameSite = validateAttributeValue("sameSite", requireNonNull(sameSite, "sameSite"));
        return this;
    }

    /**
     * Returns a newly created {@link Cookie} with the properties set so far.
     */
    public Cookie build() {
        return new DefaultCookie(name, value, valueQuoted, domain, path, maxAge, secure, httpOnly, sameSite);
    }
}
