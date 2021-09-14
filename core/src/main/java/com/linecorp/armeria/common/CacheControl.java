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

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Directives for HTTP caching mechanisms in requests or responses. Use {@link ServerCacheControl} for
 * response-side and {@link ClientCacheControl} for request-side.
 *
 * @see CacheControlBuilder
 * @see <a href="https://developer.mozilla.org/docs/Web/HTTP/Headers/Cache-Control">Cache-Control (MDN)</a>
 */
public abstract class CacheControl {

    private final boolean noCache;
    private final boolean noStore;
    private final boolean noTransform;
    private final long maxAgeSeconds;

    /**
     * Creates a new instance with the specified directives.
     *
     * @param noCache whether the {@code "no-cache"} directive is enabled.
     * @param noStore whether the {@code "no-store"} directive is enabled.
     * @param noTransform whether the {@code "no-transform"} directive is enabled.
     * @param maxAgeSeconds the value of the {@code "max-age"} directive, or {@code -1} if disabled.
     */
    CacheControl(boolean noCache, boolean noStore, boolean noTransform, long maxAgeSeconds) {
        assert maxAgeSeconds >= -1 : maxAgeSeconds;
        this.noCache = noCache;
        this.noStore = noStore;
        this.noTransform = noTransform;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    /**
     * Returns {@code true} if all directives are disabled.
     */
    public boolean isEmpty() {
        return !noCache && !noStore && !noTransform && maxAgeSeconds < 0;
    }

    /**
     * Returns whether the {@code "no-cache"} directive is enabled.
     */
    public final boolean noCache() {
        return noCache;
    }

    /**
     * Returns whether the {@code "no-store"} directive is enabled.
     */
    public final boolean noStore() {
        return noStore;
    }

    /**
     * Returns whether the {@code "no-transform"} directive is enabled.
     */
    public final boolean noTransform() {
        return noTransform;
    }

    /**
     * Returns the value of the {@code "max-age"} directive or {@code -1} if disabled.
     */
    public final long maxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * Returns a newly created {@link CacheControlBuilder} which has the same initial directives with
     * this {@link CacheControl}.
     */
    public abstract CacheControlBuilder toBuilder();

    /**
     * Encodes the directives in this {@link CacheControl} into an HTTP {@code "cache-control"} header value.
     *
     * @return the {@code "cache-control"} header value, or an empty string if no directives were enabled.
     */
    public abstract String asHeaderValue();

    /**
     * Returns a new {@link StringBuilder} with the common directives appended.
     * Note that the first two characters ({@code ", "} must be stripped.
     */
    final StringBuilder newHeaderValueBuffer() {
        final StringBuilder buf = new StringBuilder(40);
        if (noCache) {
            buf.append(", no-cache");
        }
        if (noStore) {
            buf.append(", no-store");
        }
        if (noTransform) {
            buf.append(", no-transform");
        }
        if (maxAgeSeconds >= 0) {
            buf.append(", max-age=").append(maxAgeSeconds);
        }
        return buf;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof CacheControl)) {
            return false;
        }

        final CacheControl that = (CacheControl) o;
        return noCache == that.noCache &&
               noStore == that.noStore &&
               noTransform == that.noTransform &&
               maxAgeSeconds == that.maxAgeSeconds;
    }

    @Override
    public int hashCode() {
        return (((noCache ? 1 : 0) * 31 + (noStore ? 1 : 0)) * 31 + (noTransform ? 1 : 0)) * 31 +
               (int) (maxAgeSeconds ^ (maxAgeSeconds >>> 32));
    }

    @Override
    public String toString() {
        final String value = asHeaderValue();
        return value.isEmpty() ? (getClass().getSimpleName() + "(<empty>)")
                               : (getClass().getSimpleName() + '(' + value + ')');
    }
}
