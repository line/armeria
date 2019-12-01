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
/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common;

import javax.annotation.Nullable;

/**
 * An interface defining an
 * <a href="http://en.wikipedia.org/wiki/HTTP_cookie">HTTP cookie</a>.
 */
public interface Cookie extends Comparable<Cookie> {

    // Forked from netty-4.1.43

    /**
     * Returns a newly created {@link Cookie}.
     *
     * @param name the name of the {@link Cookie}
     * @param value the value of the {@link Cookie}
     */
    static Cookie of(String name, String value) {
        return builder(name, value).build();
    }

    /**
     * Returns a newly created {@link CookieBuilder} which builds a {@link Cookie}.
     *
     * @param name the name of the {@link Cookie}
     * @param value the value of the {@link Cookie}
     */
    static CookieBuilder builder(String name, String value) {
        return new CookieBuilder(name, value);
    }

    /**
     * Constant for undefined MaxAge attribute value.
     */
    long UNDEFINED_MAX_AGE = Long.MIN_VALUE;

    /**
     * Returns the name of this {@link Cookie}.
     */
    String name();

    /**
     * Returns the value of this {@link Cookie}.
     */
    String value();

    /**
     * Returns whether the raw value of this {@link Cookie} was wrapped with double quotes
     * in the original {@code "Set-Cookie"} header.
     */
    boolean isValueQuoted();

    /**
     * Returns the domain of this {@link Cookie}.
     *
     * @return the domain, or {@code null}.
     */
    @Nullable
    String domain();

    /**
     * Returns the path of this {@link Cookie}.
     *
     * @return the path, or {@code null}.
     */
    @Nullable
    String path();

    /**
     * Returns the maximum age of this {@link Cookie} in seconds.
     *
     * @return the maximum age, or {@link Cookie#UNDEFINED_MAX_AGE} if unspecified.
     */
    long maxAge();

    /**
     * Returns whether this {@link Cookie} is secure.
     */
    boolean isSecure();

    /**
     * Returns whether this {@link Cookie} can only be accessed via HTTP.
     * If this returns {@code true}, the {@link Cookie} cannot be accessed through client side script.
     * However, it works only if the browser supports it.
     * Read <a href="http://www.owasp.org/index.php/HTTPOnly">here</a> for more information.
     */
    boolean isHttpOnly();

    /**
     * Returns the <a href="https://tools.ietf.org/html/draft-ietf-httpbis-rfc6265bis-03#section-4.1.2.7"
     * >{@code "SameSite"}</a> attribute of this {@link Cookie}.
     *
     * @return the {@code "SameSite"} attribute, or {@code null}.
     */
    @Nullable
    String sameSite();
}
