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

import com.google.common.collect.ForwardingSet;

import io.netty.handler.codec.http.cookie.Cookie;

/**
 * A default implementation of {@link Cookies} interface.
 */
final class DefaultCookies extends ForwardingSet<Cookie> implements Cookies {

    private final Set<Cookie> delegate;

    DefaultCookies(Set<Cookie> delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    protected Set<Cookie> delegate() {
        return delegate;
    }
}
