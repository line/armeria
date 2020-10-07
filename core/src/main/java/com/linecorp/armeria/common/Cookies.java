/*
 * Copyright 2018 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

/**
 * An immutable {@link Set} of {@link Cookie}s.
 */
public interface Cookies extends Set<Cookie> {

    /**
     * Returns an immutable empty {@link Set} of {@link Cookie}s.
     */
    static Cookies of() {
        return DefaultCookies.EMPTY;
    }

    /**
     * Creates an instance with a copy of the specified set of {@link Cookie}s.
     */
    static Cookies of(Cookie... cookies) {
        requireNonNull(cookies, "cookies");
        if (cookies.length == 0) {
            return of();
        } else {
            return new DefaultCookies(ImmutableSet.copyOf(cookies));
        }
    }

    /**
     * Creates an instance with a copy of the specified set of {@link Cookie}s.
     */
    static Cookies of(Iterable<? extends Cookie> cookies) {
        final ImmutableSet<Cookie> cookiesCopy = ImmutableSet.copyOf(requireNonNull(cookies, "cookies"));
        if (cookiesCopy.isEmpty()) {
            return of();
        } else {
            return new DefaultCookies(cookiesCopy);
        }
    }
}
