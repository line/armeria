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
package com.linecorp.armeria.server.annotation;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import io.netty.handler.codec.http.cookie.Cookie;

/**
 * An interface which holds decoded {@link Cookie} instances for an HTTP request.
 */
public interface Cookies extends Set<Cookie> {

    /**
     * Creates an instance with a copy of the specified set of {@link Cookie}s.
     */
    static Cookies of(Cookie... cookies) {
        return new DefaultCookies(ImmutableSet.copyOf(requireNonNull(cookies, "cookies")));
    }

    /**
     * Creates an instance with a copy of the specified {@link Iterable} of {@link Cookie}s.
     */
    static Cookies copyOf(Iterable<? extends Cookie> cookies) {
        return new DefaultCookies(ImmutableSet.copyOf(requireNonNull(cookies, "cookies")));
    }
}
