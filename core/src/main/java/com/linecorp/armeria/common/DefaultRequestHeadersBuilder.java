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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

final class DefaultRequestHeadersBuilder extends AbstractHttpHeadersBuilder<RequestHeadersBuilder>
        implements RequestHeadersBuilder {

    DefaultRequestHeadersBuilder() {}

    DefaultRequestHeadersBuilder(DefaultRequestHeaders headers) {
        super(headers);
    }

    @Override
    public RequestHeaders build() {
        final HttpHeadersBase delegate = delegate();
        if (delegate != null) {
            checkState(delegate.contains(HttpHeaderNames.METHOD), ":method header does not exist.");
            checkState(delegate.contains(HttpHeaderNames.PATH), ":path header does not exist.");
            return new DefaultRequestHeaders(promoteDelegate());
        }

        final HttpHeadersBase parent = parent();
        if (parent != null) {
            if (parent instanceof RequestHeaders) {
                return (RequestHeaders) parent;
            } else {
                return updateParent(new DefaultRequestHeaders(parent));
            }
        }

        // No headers were set.
        throw new IllegalStateException("must set ':method' and ':path' headers");
    }

    // Shortcuts

    @Override
    public URI uri() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, "must set ':scheme', ':authority' and ':path' headers");
        return getters.uri();
    }

    @Override
    public HttpMethod method() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":method header does not exist.");
        return getters.method();
    }

    @Override
    public RequestHeadersBuilder method(HttpMethod method) {
        setters().method(method);
        return this;
    }

    @Override
    public String path() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":path header does not exist.");
        return getters.path();
    }

    @Override
    public RequestHeadersBuilder path(String path) {
        setters().path(path);
        return this;
    }

    @Nullable
    @Override
    public String scheme() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.scheme() : null;
    }

    @Override
    public RequestHeadersBuilder scheme(String scheme) {
        setters().scheme(scheme);
        return this;
    }

    @Nullable
    @Override
    public String authority() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.authority() : null;
    }

    @Override
    public RequestHeadersBuilder authority(String authority) {
        setters().authority(authority);
        return this;
    }

    @Override
    @Nullable
    public List<LanguageRange> acceptLanguages() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.acceptLanguages() : null;
    }

    @Override
    public RequestHeadersBuilder acceptLanguages(Iterable<LanguageRange> acceptLanguages) {
        requireNonNull(acceptLanguages, "acceptLanguages");
        checkArgument(!Iterables.isEmpty(acceptLanguages), "acceptLanguages cannot be empty");
        setters().acceptLanguages(acceptLanguages);
        return self();
    }

    @Nullable
    @Override
    public Locale selectLocale(Iterable<Locale> supportedLocales) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.selectLocale(supportedLocales) : null;
    }

    @Override
    public RequestHeadersBuilder cookie(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        return cookies(ImmutableSet.of(cookie));
    }

    @Override
    public Cookies cookies() {
        final HttpHeadersBase getters = getters();
        if (getters == null) {
            return Cookies.of();
        }
        return getters.cookie();
    }

    @Override
    public RequestHeadersBuilder cookies(Iterable<? extends Cookie> cookies) {
        requireNonNull(cookies, "cookie");
        setters().cookie(cookies);
        return this;
    }

    @Override
    public RequestHeadersBuilder cookies(Cookie... cookies) {
        requireNonNull(cookies, "cookie");
        return cookies(ImmutableSet.copyOf(cookies));
    }

    @Override
    public List<MediaType> accept() {
        final HttpHeadersBase getters = getters();
        if (getters == null) {
            return ImmutableList.of();
        }
        return getters.accept();
    }

    @Override
    public RequestHeadersBuilder accept(MediaType... mediaTypes) {
        requireNonNull(mediaTypes, "mediaTypes");
        return accept(ImmutableList.copyOf(mediaTypes));
    }

    @Override
    public RequestHeadersBuilder accept(Iterable<MediaType> mediaTypes) {
        setters().accept(mediaTypes);
        return this;
    }
}
