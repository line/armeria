/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.cookie;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Decorates an {@link HttpClient} to set cookies to {@code Cookie} header of outgoing {@link HttpRequest} and
 * store cookies from {@code Set-Cookie} headers of incoming {@link HttpResponse}.
 */
public final class CookieClient extends SimpleDecoratingHttpClient {

    /**
     * Creates a new {@link CookieClient} decorator.
     */
    public static Function<? super HttpClient, CookieClient> newDecorator() {
        return newDecorator(new DefaultCookieJar());
    }

    /**
     * Creates a new {@link CookieClient} decorator with a specified {@link CookiePolicy}.
     */
    public static Function<? super HttpClient, CookieClient> newDecorator(CookiePolicy cookiePolicy) {
        requireNonNull(cookiePolicy, "cookiePolicy");
        return newDecorator(new DefaultCookieJar(cookiePolicy));
    }

    /**
     * Creates a new {@link CookieClient} decorator with a {@link CookieJar} implementation.
     */
    public static Function<? super HttpClient, CookieClient> newDecorator(CookieJar cookieJar) {
        requireNonNull(cookieJar, "cookieJar");
        return client -> new CookieClient(client, cookieJar);
    }

    private final CookieJar cookieJar;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    private CookieClient(HttpClient delegate, CookieJar cookieJar) {
        super(delegate);
        this.cookieJar = cookieJar;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final URI uri = req.uri();
        final Cookies cookies = cookieJar.get(uri);
        if (!cookies.isEmpty()) {
            final String cookieHeader = Cookie.toCookieHeader(cookies);
            req = req.withHeaders(req.headers().toBuilder().add(HttpHeaderNames.COOKIE, cookieHeader));
            ctx.updateRequest(req);
        }
        final HttpResponse res = unwrap().execute(ctx, req);
        return new SetCookieResponse(res, setCookieHeaders ->
                cookieJar.set(uri, Cookie.fromSetCookieHeaders(setCookieHeaders)));
    }
}
