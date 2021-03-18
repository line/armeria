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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.parseDirectiveValueAsSeconds;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.parseDirectives;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Directives for HTTP caching mechanisms in requests.
 *
 * @see ClientCacheControlBuilder
 * @see <a href="https://developer.mozilla.org/docs/Web/HTTP/Headers/Cache-Control">Cache-Control (MDN)</a>
 * @see <a href="https://stackoverflow.com/q/14541077">Why is Cache-Control header sent in a request?</a>
 */
public final class ClientCacheControl extends CacheControl {

    /**
     * An empty instance with all directives disabled.
     */
    public static final ClientCacheControl EMPTY = builder().build();

    /**
     * {@code "no-cache"}.
     */
    public static final ClientCacheControl FORCE_NETWORK = builder().noCache().build();

    /**
     * {@code "only-if-cached, max-stale=2147483647"}.
     */
    public static final ClientCacheControl FORCE_CACHE = builder().onlyIfCached()
                                                                  .maxStaleSeconds(Integer.MAX_VALUE)
                                                                  .build();

    private static final Map<String, BiConsumer<ClientCacheControlBuilder, String>> DIRECTIVES =
            ImmutableMap.<String, BiConsumer<ClientCacheControlBuilder, String>>builder()
                    .put("no-cache", (b, v) -> b.noCache())
                    .put("no-store", (b, v) -> b.noStore())
                    .put("no-transform", (b, v) -> b.noTransform())
                    .put("max-age", (b, v) -> {
                        final long maxAgeSeconds = parseDirectiveValueAsSeconds(v);
                        if (maxAgeSeconds >= 0) {
                            b.maxAgeSeconds(maxAgeSeconds);
                        }
                    })
                    .put("only-if-cached", (b, v) -> b.onlyIfCached())
                    .put("max-stale", (b, v) -> {
                        if (v == null) {
                            b.maxStale();
                        } else {
                            final long maxStaleSeconds = parseDirectiveValueAsSeconds(v);
                            if (maxStaleSeconds >= 0) {
                                b.maxStaleSeconds(maxStaleSeconds);
                            }
                        }
                    })
                    .put("min-fresh", (b, v) -> {
                        final long minFreshSeconds = parseDirectiveValueAsSeconds(v);
                        if (minFreshSeconds >= 0) {
                            b.minFreshSeconds(minFreshSeconds);
                        }
                    })
                    .put("stale-while-revalidate", (b, v) -> {
                        final long staleWhileRevalidateSeconds = parseDirectiveValueAsSeconds(v);
                        if (staleWhileRevalidateSeconds >= 0) {
                            b.staleWhileRevalidateSeconds(staleWhileRevalidateSeconds);
                        }
                    })
                    .put("stale-if-error", (b, v) -> {
                        final long staleIfErrorSeconds = parseDirectiveValueAsSeconds(v);
                        if (staleIfErrorSeconds >= 0) {
                            b.staleIfErrorSeconds(staleIfErrorSeconds);
                        }
                    })
                    .build();

    /**
     * Parses the specified {@code "cache-control"} header values into a {@link ClientCacheControl}.
     * Note that any unknown directives will be ignored.
     *
     * @return the {@link ClientCacheControl} decoded from the specified header values.
     */
    public static ClientCacheControl parse(String... directives) {
        return parse(ImmutableList.copyOf(requireNonNull(directives, "directives")));
    }

    /**
     * Parses the specified {@code "cache-control"} header values into a {@link ClientCacheControl}.
     * Note that any unknown directives will be ignored.
     *
     * @return the {@link ClientCacheControl} decoded from the specified header values.
     */
    public static ClientCacheControl parse(Iterable<String> directives) {
        requireNonNull(directives, "directives");
        final ClientCacheControlBuilder builder = builder();
        for (String d : directives) {
            parseDirectives(d, (name, value) -> {
                final BiConsumer<ClientCacheControlBuilder, String> action = DIRECTIVES.get(name);
                if (action != null) {
                    action.accept(builder, value);
                }
            });
        }

        return builder.build();
    }

    /**
     * Returns a newly created {@link ClientCacheControlBuilder} with all directives disabled initially.
     */
    public static ClientCacheControlBuilder builder() {
        return new ClientCacheControlBuilder();
    }

    static final long UNSPECIFIED_MAX_STALE = -2;

    private final boolean onlyIfCached;
    final long maxStaleSeconds;
    private final long minFreshSeconds;
    private final long staleWhileRevalidateSeconds;
    private final long staleIfErrorSeconds;
    @Nullable
    private String headerValue;

    ClientCacheControl(boolean noCache, boolean noStore, boolean noTransform, long maxAgeSeconds,
                       boolean onlyIfCached, long maxStaleSeconds, long minFreshSeconds,
                       long staleWhileRevalidateSeconds, long staleIfErrorSeconds) {
        super(noCache, noStore, noTransform, maxAgeSeconds);
        this.onlyIfCached = onlyIfCached;
        this.maxStaleSeconds = maxStaleSeconds;
        this.minFreshSeconds = minFreshSeconds;
        this.staleWhileRevalidateSeconds = staleWhileRevalidateSeconds;
        this.staleIfErrorSeconds = staleIfErrorSeconds;
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty() && !onlyIfCached && !hasMaxStale() && minFreshSeconds < 0 &&
               staleWhileRevalidateSeconds < 0 && staleIfErrorSeconds < 0;
    }

    /**
     * Returns whether the {@code "only-if-cached"} directive is enabled.
     */
    public boolean onlyIfCached() {
        return onlyIfCached;
    }

    /**
     * Returns whether the {@code "max-stale"} directive is enabled.
     *
     * @see #maxStaleSeconds()
     */
    public boolean hasMaxStale() {
        return maxStaleSeconds >= 0 || maxStaleSeconds == UNSPECIFIED_MAX_STALE;
    }

    /**
     * Returns the value of the {@code "max-stale"} directive or {@code -1} if disabled or the value is not
     * specified.
     *
     * @see #hasMaxStale()
     */
    public long maxStaleSeconds() {
        return maxStaleSeconds < 0 ? -1 : maxStaleSeconds;
    }

    /**
     * Returns the value of the {@code "min-fresh"} directive or {@code -1} if disabled.
     */
    public long minFreshSeconds() {
        return minFreshSeconds;
    }

    /**
     * Returns the value of the {@code "stale-while-revalidate"} directive or {@code -1} if disabled.
     */
    public long staleWhileRevalidateSeconds() {
        return staleWhileRevalidateSeconds;
    }

    /**
     * Returns the value of the {@code "stale-if-error"} directive or {@code -1} if disabled.
     */
    public long staleIfErrorSeconds() {
        return staleIfErrorSeconds;
    }

    /**
     * Returns a newly created {@link ClientCacheControlBuilder} which has the same initial directives with
     * this {@link ClientCacheControl}.
     */
    @Override
    public ClientCacheControlBuilder toBuilder() {
        return new ClientCacheControlBuilder(this);
    }

    @Override
    public String asHeaderValue() {
        if (headerValue != null) {
            return headerValue;
        }

        final StringBuilder buf = newHeaderValueBuffer();
        if (onlyIfCached) {
            buf.append(", only-if-cached");
        }
        if (maxStaleSeconds >= 0) {
            buf.append(", max-stale=").append(maxStaleSeconds);
        } else if (maxStaleSeconds == UNSPECIFIED_MAX_STALE) {
            buf.append(", max-stale");
        }
        if (minFreshSeconds >= 0) {
            buf.append(", min-fresh=").append(minFreshSeconds);
        }
        if (staleWhileRevalidateSeconds >= 0) {
            buf.append(", stale-while-revalidate=").append(staleWhileRevalidateSeconds);
        }
        if (staleIfErrorSeconds >= 0) {
            buf.append(", stale-if-error=").append(staleIfErrorSeconds);
        }

        if (buf.length() == 0) {
            return headerValue = "";
        } else {
            return headerValue = buf.substring(2);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!super.equals(o)) {
            return false;
        }

        final ClientCacheControl that = (ClientCacheControl) o;
        return onlyIfCached == that.onlyIfCached &&
               maxStaleSeconds == that.maxStaleSeconds &&
               minFreshSeconds == that.minFreshSeconds &&
               staleWhileRevalidateSeconds == that.staleWhileRevalidateSeconds &&
               staleIfErrorSeconds == that.staleIfErrorSeconds;
    }

    @Override
    public int hashCode() {
        return ((((super.hashCode() * 31 + (onlyIfCached ? 1 : 0)) * 31 +
                  (int) (maxStaleSeconds ^ (maxStaleSeconds >>> 32))) * 31 +
                 (int) (minFreshSeconds ^ (minFreshSeconds >>> 32))) * 31 +
                (int) (staleWhileRevalidateSeconds ^ (staleWhileRevalidateSeconds >>> 32))) * 31 +
               (int) (staleIfErrorSeconds ^ (staleIfErrorSeconds >>> 32));
    }
}
