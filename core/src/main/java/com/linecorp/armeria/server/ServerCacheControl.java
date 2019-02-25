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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.parseCacheControl;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.parseCacheControlSeconds;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CacheControl;

/**
 * Directives for HTTP caching mechanisms in responses.
 *
 * @see ServerCacheControlBuilder
 * @see <a href="https://developers.google.com/web/fundamentals/performance/optimizing-content-efficiency/http-caching">HTTP Caching (Google)</a>
 * @see <a href="https://developer.mozilla.org/docs/Web/HTTP/Headers/Cache-Control">Cache-Control (MDN)</a>
 */
public final class ServerCacheControl extends CacheControl {

    /**
     * An empty instance with all directives disabled.
     */
    public static final ServerCacheControl EMPTY = new ServerCacheControlBuilder().build();

    /**
     * {@code "no-cache, no-store, must-revalidate"}.
     */
    public static final ServerCacheControl DISABLED = new ServerCacheControlBuilder()
            .noCache().noStore().mustRevalidate().build();

    /**
     * {@code "no-cache"}.
     */
    public static final ServerCacheControl REVALIDATED = new ServerCacheControlBuilder()
            .noCache().build();

    /**
     * {@code "max-age=31536000, public, immutable"}.
     */
    public static final ServerCacheControl IMMUTABLE = new ServerCacheControlBuilder()
            .maxAgeSeconds(31536000).cachePublic().immutable().build();

    /**
     * Parses the specified {@code "cache-control"} header values into a {@link ServerCacheControl}.
     * Note that any unknown directives will be ignored.
     *
     * @return the {@link ServerCacheControl} decoded from the specified header values.
     */
    public static ServerCacheControl parse(String... directives) {
        return parse(ImmutableList.copyOf(requireNonNull(directives, "directives")));
    }

    /**
     * Parses the specified {@code "cache-control"} header values into a {@link ServerCacheControl}.
     * Note that any unknown directives will be ignored.
     *
     * @return the {@link ServerCacheControl} decoded from the specified header values.
     */
    public static ServerCacheControl parse(Iterable<String> directives) {
        requireNonNull(directives, "directives");
        final ServerCacheControlBuilder builder = new ServerCacheControlBuilder();
        for (String d : directives) {
            parseCacheControl(d, (name, value) -> {
                switch (name) {
                    case "no-cache":
                        builder.noCache();
                        break;
                    case "no-store":
                        builder.noStore();
                        break;
                    case "no-transform":
                        builder.noTransform();
                        break;
                    case "max-age":
                        final long maxAgeSeconds = parseCacheControlSeconds(value);
                        if (maxAgeSeconds >= 0) {
                            builder.maxAgeSeconds(maxAgeSeconds);
                        }
                        break;
                    case "public":
                        builder.cachePublic();
                        break;
                    case "private":
                        builder.cachePrivate();
                        break;
                    case "immutable":
                        builder.immutable();
                        break;
                    case "must-revalidate":
                        builder.mustRevalidate();
                        break;
                    case "proxy-revalidate":
                        builder.proxyRevalidate();
                        break;
                    case "s-maxage":
                        final long sMaxAgeSeconds = parseCacheControlSeconds(value);
                        if (sMaxAgeSeconds >= 0) {
                            builder.sMaxAgeSeconds(sMaxAgeSeconds);
                        }
                        break;
                }
            });
        }

        return builder.build();
    }

    private final boolean cachePublic;
    private final boolean cachePrivate;
    private final boolean immutable;
    private final boolean mustRevalidate;
    private final boolean proxyRevalidate;
    private final long sMaxAgeSeconds;
    @Nullable
    private String headerValue;

    ServerCacheControl(boolean noCache, boolean noStore, boolean noTransform, long maxAgeSeconds,
                       boolean cachePublic, boolean cachePrivate, boolean immutable,
                       boolean mustRevalidate, boolean proxyRevalidate, long sMaxAgeSeconds) {
        super(noCache, noStore, noTransform, maxAgeSeconds);
        this.cachePublic = cachePublic;
        this.cachePrivate = cachePrivate;
        this.immutable = immutable;
        this.mustRevalidate = mustRevalidate;
        this.proxyRevalidate = proxyRevalidate;
        this.sMaxAgeSeconds = sMaxAgeSeconds;
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty() && !cachePublic && !cachePrivate && !immutable &&
               !mustRevalidate && !proxyRevalidate && sMaxAgeSeconds < 0;
    }

    /**
     * Returns whether the {@code "public"} directive is enabled.
     */
    public boolean cachePublic() {
        return cachePublic;
    }

    /**
     * Returns whether the {@code "private"} directive is enabled.
     */
    public boolean cachePrivate() {
        return cachePrivate;
    }

    /**
     * Returns whether the {@code "immutable"} directive is enabled.
     */
    public boolean immutable() {
        return immutable;
    }

    /**
     * Returns whether the {@code "must-revalidate"} directive is enabled.
     */
    public boolean mustRevalidate() {
        return mustRevalidate;
    }

    /**
     * Returns whether the {@code "proxy-revalidate"} directive is enabled.
     */
    public boolean proxyRevalidate() {
        return proxyRevalidate;
    }

    /**
     * Returns the value of the {@code "s-maxage"} directive or {@code -1} if disabled.
     */
    public long sMaxAgeSeconds() {
        return sMaxAgeSeconds;
    }

    /**
     * Returns a newly created {@link ServerCacheControlBuilder} which has the same initial directives with
     * this {@link ServerCacheControl}.
     */
    @Override
    public ServerCacheControlBuilder toBuilder() {
        return new ServerCacheControlBuilder(this);
    }

    @Override
    public String asHeaderValue() {
        if (headerValue != null) {
            return headerValue;
        }

        final StringBuilder buf = newHeaderValueBuffer();
        if (cachePublic) {
            buf.append(", public");
        }
        if (cachePrivate) {
            buf.append(", private");
        }
        if (immutable) {
            buf.append(", immutable");
        }
        if (mustRevalidate) {
            buf.append(", must-revalidate");
        }
        if (proxyRevalidate) {
            buf.append(", proxy-revalidate");
        }
        if (sMaxAgeSeconds >= 0) {
            buf.append(", s-maxage=").append(sMaxAgeSeconds);
        }

        if (buf.length() == 0) {
            return headerValue = "";
        } else {
            return headerValue = buf.substring(2);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }

        final ServerCacheControl that = (ServerCacheControl) o;
        return cachePublic == that.cachePublic &&
               cachePrivate == that.cachePrivate &&
               immutable == that.immutable &&
               mustRevalidate == that.mustRevalidate &&
               proxyRevalidate == that.proxyRevalidate &&
               sMaxAgeSeconds == that.sMaxAgeSeconds;
    }

    @Override
    public int hashCode() {
        return (((((super.hashCode() * 31 +
                    (cachePublic ? 1 : 0)) * 31 +
                   (cachePrivate ? 1 : 0)) * 31 +
                  (immutable ? 1 : 0)) * 31 +
                 (mustRevalidate ? 1 : 0)) * 31 +
                (proxyRevalidate ? 1 : 0)) * 31 +
               (int) (sMaxAgeSeconds ^ (sMaxAgeSeconds >>> 32));
    }
}
