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

import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Creates a new {@link ClientCacheControl} using the builder pattern.
 *
 * <pre>{@code
 * ClientCacheControl cacheControl =
 *     ClientCacheControl.builder().noCache().build();
 * }</pre>
 *
 * @see ServerCacheControlBuilder
 * @see <a href="https://developer.mozilla.org/docs/Web/HTTP/Headers/Cache-Control">Cache-Control (MDN)</a>
 * @see <a href="https://stackoverflow.com/q/14541077">Why is Cache-Control header sent in a request?</a>
 */
public final class ClientCacheControlBuilder extends CacheControlBuilder {

    private boolean onlyIfCached;
    private long maxStaleSeconds = -1;
    private long minFreshSeconds = -1;
    private long staleWhileRevalidateSeconds = -1;
    private long staleIfErrorSeconds = -1;

    ClientCacheControlBuilder() {}

    ClientCacheControlBuilder(ClientCacheControl c) {
        super(c);
        onlyIfCached = c.onlyIfCached();
        maxStaleSeconds = c.maxStaleSeconds;
        minFreshSeconds = c.minFreshSeconds();
        staleWhileRevalidateSeconds = c.staleWhileRevalidateSeconds();
        staleIfErrorSeconds = c.staleIfErrorSeconds();
    }

    /**
     * Enables the {@code "only-if-cached"} directive.
     */
    public ClientCacheControlBuilder onlyIfCached() {
        return onlyIfCached(true);
    }

    /**
     * Enables or disables the {@code "only-if-cached"} directive.
     *
     * @param onlyIfCached {@code true} to enable or {@code false} to disable.
     */
    public ClientCacheControlBuilder onlyIfCached(boolean onlyIfCached) {
        this.onlyIfCached = onlyIfCached;
        return this;
    }

    /**
     * Enables the {@code "max-stale} directive without a value.
     */
    public ClientCacheControlBuilder maxStale() {
        return maxStale(true);
    }

    /**
     * Enables or disables the {@code "max-stale"} directive.
     *
     * @param maxStale {@code true} to enable without a value or {@code false} to disable.
     */
    public ClientCacheControlBuilder maxStale(boolean maxStale) {
        maxStaleSeconds = maxStale ? ClientCacheControl.UNSPECIFIED_MAX_STALE : -1;
        return this;
    }

    /**
     * Enables or disables the {@code "max-stale"} directive.
     *
     * @param maxStale the value of the directive to enable, or {@code null} to disable.
     */
    public ClientCacheControlBuilder maxStale(@Nullable Duration maxStale) {
        maxStaleSeconds = validateDuration(maxStale, "maxStale");
        return this;
    }

    /**
     * Enables the {@code "max-stale"} directive.
     *
     * @param maxStaleSeconds the value in seconds.
     */
    public ClientCacheControlBuilder maxStaleSeconds(long maxStaleSeconds) {
        this.maxStaleSeconds = validateSeconds(maxStaleSeconds, "maxStaleSeconds");
        return this;
    }

    /**
     * Enables or disables the {@code "min-fresh"} directive.
     *
     * @param minFresh the value of the directive to enable, or {@code null} to disable.
     */
    public ClientCacheControlBuilder minFresh(@Nullable Duration minFresh) {
        minFreshSeconds = validateDuration(minFresh, "minFresh");
        return this;
    }

    /**
     * Enables the {@code "min-fresh"} directive.
     *
     * @param minFreshSeconds the value in seconds.
     */
    public ClientCacheControlBuilder minFreshSeconds(long minFreshSeconds) {
        this.minFreshSeconds = validateSeconds(minFreshSeconds, "minFreshSeconds");
        return this;
    }

    /**
     * Enables or disables the {@code "stale-while-revalidate"} directive.
     *
     * @param staleWhileRevalidate the value of the directive to enable, or {@code null} to disable.
     */
    public ClientCacheControlBuilder staleWhileRevalidate(@Nullable Duration staleWhileRevalidate) {
        staleWhileRevalidateSeconds = validateDuration(staleWhileRevalidate, "staleWhileRevalidate");
        return this;
    }

    /**
     * Enables the {@code "stale-while-revalidate"} directive.
     *
     * @param staleWhileRevalidateSeconds the value in seconds.
     */
    public ClientCacheControlBuilder staleWhileRevalidateSeconds(long staleWhileRevalidateSeconds) {
        this.staleWhileRevalidateSeconds = validateSeconds(staleWhileRevalidateSeconds,
                                                           "staleWhileRevalidateSeconds");
        return this;
    }

    /**
     * Enables or disables the {@code "stale-if-error"} directive.
     *
     * @param staleIfError the value of the directive to enable, or {@code null} to disable.
     */
    public ClientCacheControlBuilder staleIfError(@Nullable Duration staleIfError) {
        staleIfErrorSeconds = validateDuration(staleIfError, "staleIfError");
        return this;
    }

    /**
     * Enables the {@code "stale-if-error"} directive.
     *
     * @param staleIfErrorSeconds the value in seconds.
     */
    public ClientCacheControlBuilder staleIfErrorSeconds(long staleIfErrorSeconds) {
        this.staleIfErrorSeconds = validateSeconds(staleIfErrorSeconds, "staleIfErrorSeconds");
        return this;
    }

    /**
     * Returns a newly created {@link ClientCacheControl} with the directives enabled so far.
     */
    @Override
    public ClientCacheControl build() {
        return (ClientCacheControl) super.build();
    }

    @Override
    ClientCacheControl build(boolean noCache, boolean noStore, boolean noTransform, long maxAgeSeconds) {
        return new ClientCacheControl(noCache, noStore, noTransform, maxAgeSeconds,
                                      onlyIfCached, maxStaleSeconds, minFreshSeconds,
                                      staleWhileRevalidateSeconds, staleIfErrorSeconds);
    }

    // Overridden to change the return type.

    @Override
    public ClientCacheControlBuilder noCache() {
        return (ClientCacheControlBuilder) super.noCache();
    }

    @Override
    public ClientCacheControlBuilder noCache(boolean noCache) {
        return (ClientCacheControlBuilder) super.noCache(noCache);
    }

    @Override
    public ClientCacheControlBuilder noStore() {
        return (ClientCacheControlBuilder) super.noStore();
    }

    @Override
    public ClientCacheControlBuilder noStore(boolean noStore) {
        return (ClientCacheControlBuilder) super.noStore(noStore);
    }

    @Override
    public ClientCacheControlBuilder noTransform() {
        return (ClientCacheControlBuilder) super.noTransform();
    }

    @Override
    public ClientCacheControlBuilder noTransform(boolean noTransform) {
        return (ClientCacheControlBuilder) super.noTransform(noTransform);
    }

    @Override
    public ClientCacheControlBuilder maxAge(@Nullable Duration maxAge) {
        return (ClientCacheControlBuilder) super.maxAge(maxAge);
    }

    @Override
    public ClientCacheControlBuilder maxAgeSeconds(long maxAgeSeconds) {
        return (ClientCacheControlBuilder) super.maxAgeSeconds(maxAgeSeconds);
    }
}
