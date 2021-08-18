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

import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal builder implementation of {@link CacheControl}. Use {@link ServerCacheControlBuilder} for
 * building {@link ServerCacheControl} and {@link ClientCacheControlBuilder} for building
 * {@link ClientCacheControl}.
 *
 * @see <a href="https://developer.mozilla.org/docs/Web/HTTP/Headers/Cache-Control">Cache-Control (MDN)</a>
 */
abstract class CacheControlBuilder {

    private boolean noCache;
    private boolean noStore;
    private boolean noTransform;
    private long maxAgeSeconds = -1;

    /**
     * Creates a new builder with all directives disabled initially.
     */
    CacheControlBuilder() {}

    /**
     * Creates a new builder using the specified {@link CacheControl} as the initial directives.
     */
    CacheControlBuilder(CacheControl c) {
        noCache = c.noCache();
        noStore = c.noStore();
        noTransform = c.noTransform();
        maxAgeSeconds = c.maxAgeSeconds();
    }

    /**
     * Enables the {@code "no-cache"} directive.
     */
    public CacheControlBuilder noCache() {
        return noCache(true);
    }

    /**
     * Enables or disables the {@code "no-cache"} directive.
     *
     * @param noCache {@code true} to enable or {@code false} to disable.
     */
    public CacheControlBuilder noCache(boolean noCache) {
        this.noCache = noCache;
        return this;
    }

    /**
     * Enables the {@code "no-store"} directive.
     */
    public CacheControlBuilder noStore() {
        return noStore(true);
    }

    /**
     * Enables or disables the {@code "no-store"} directive.
     *
     * @param noStore {@code true} to enable or {@code false} to disable.
     */
    public CacheControlBuilder noStore(boolean noStore) {
        this.noStore = noStore;
        return this;
    }

    /**
     * Enables the {@code "no-transform"} directive.
     */
    public CacheControlBuilder noTransform() {
        return noTransform(true);
    }

    /**
     * Enables or disables the {@code "no-transform"} directive.
     *
     * @param noTransform {@code true} to enable or {@code false} to disable.
     */
    public CacheControlBuilder noTransform(boolean noTransform) {
        this.noTransform = noTransform;
        return this;
    }

    /**
     * Enables or disables the {@code "max-age"} directive.
     *
     * @param maxAge the value of the directive to enable, or {@code null} to disable.
     */
    public CacheControlBuilder maxAge(@Nullable Duration maxAge) {
        maxAgeSeconds = validateDuration(maxAge, "maxAge");
        return this;
    }

    /**
     * Makes sure the specified {@link Duration} is not negative.
     *
     * @param value the duration
     * @param name the name of the parameter
     * @return the duration converted into seconds
     */
    static long validateDuration(@Nullable Duration value, String name) {
        if (value == null) {
            return -1;
        }

        checkArgument(!value.isNegative(), "%s: %s (expected: >= 0)", name, value);
        return value.getSeconds();
    }

    /**
     * Enables the {@code "max-age"} directive.
     *
     * @param maxAgeSeconds the value in seconds.
     */
    public CacheControlBuilder maxAgeSeconds(long maxAgeSeconds) {
        this.maxAgeSeconds = validateSeconds(maxAgeSeconds, "maxAgeSeconds");
        return this;
    }

    /**
     * Makes sure the specified {@code value} is not negative.
     *
     * @param value the value in seconds
     * @param name the name of the parameter
     * @return {@code value}
     */
    static long validateSeconds(long value, String name) {
        checkArgument(value >= 0, "%s: %s (expected: >= 0)", name, value);
        return value;
    }

    /**
     * Returns a newly created {@link CacheControl} with the directives enabled so far.
     */
    public CacheControl build() {
        // Note: 'no-cache' and 'no-store' are mutually exclusive, but we need to allow a user to specify
        //       both to work around known browser issues. See: https://stackoverflow.com/q/866822
        return build(noCache, noStore, noTransform, maxAgeSeconds);
    }

    /**
     * Returns a newly created instance with the specified properties.
     */
    abstract CacheControl build(boolean noCache, boolean noStore,
                                boolean noTransform, long maxAgeSeconds);
}
