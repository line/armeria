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
 * Creates a new {@link ServerCacheControl} using the builder pattern.
 *
 * <pre>{@code
 * ServerCacheControl cacheControl =
 *     ServerCacheControl.builder()
 *                       .noCache()
 *                       .noStore()
 *                       .mustRevalidate()
 *                       .build();
 * }</pre>
 *
 * @see ClientCacheControlBuilder
 * @see <a href="https://developers.google.com/web/fundamentals/performance/optimizing-content-efficiency/http-caching">HTTP Caching (Google)</a>
 * @see <a href="https://developer.mozilla.org/docs/Web/HTTP/Headers/Cache-Control">Cache-Control (MDN)</a>
 */
public final class ServerCacheControlBuilder extends CacheControlBuilder {

    private boolean cachePublic;
    private boolean cachePrivate;
    private boolean immutable;
    private boolean mustRevalidate;
    private boolean proxyRevalidate;
    private long sMaxAgeSeconds = -1;

    ServerCacheControlBuilder() {}

    ServerCacheControlBuilder(ServerCacheControl c) {
        super(c);
        cachePublic = c.cachePublic();
        cachePrivate = c.cachePrivate();
        immutable = c.immutable();
        mustRevalidate = c.mustRevalidate();
        proxyRevalidate = c.proxyRevalidate();
        sMaxAgeSeconds = c.sMaxAgeSeconds();
    }

    /**
     * Enables the {@code "public"} directive.
     */
    public ServerCacheControlBuilder cachePublic() {
        return cachePublic(true);
    }

    /**
     * Enables or disables the {@code "public"} directive.
     *
     * @param cachePublic {@code true} to enable or {@code false} to disable.
     */
    public ServerCacheControlBuilder cachePublic(boolean cachePublic) {
        this.cachePublic = cachePublic;
        return this;
    }

    /**
     * Enables the {@code "private"} directive.
     */
    public ServerCacheControlBuilder cachePrivate() {
        return cachePrivate(true);
    }

    /**
     * Enables or disables the {@code "private"} directive.
     *
     * @param cachePrivate {@code true} to enable or {@code false} to disable.
     */
    public ServerCacheControlBuilder cachePrivate(boolean cachePrivate) {
        this.cachePrivate = cachePrivate;
        return this;
    }

    /**
     * Enables the {@code "immutable"} directive.
     */
    public ServerCacheControlBuilder immutable() {
        return immutable(true);
    }

    /**
     * Enables or disables the {@code "immutable"} directive.
     *
     * @param immutable {@code true} to enable or {@code false} to disable.
     */
    public ServerCacheControlBuilder immutable(boolean immutable) {
        this.immutable = immutable;
        return this;
    }

    /**
     * Enables the {@code "must-revalidate"} directive.
     */
    public ServerCacheControlBuilder mustRevalidate() {
        return mustRevalidate(true);
    }

    /**
     * Enables or disables the {@code "must-revalidate"} directive.
     *
     * @param mustRevalidate {@code true} to enable or {@code false} to disable.
     */
    public ServerCacheControlBuilder mustRevalidate(boolean mustRevalidate) {
        this.mustRevalidate = mustRevalidate;
        return this;
    }

    /**
     * Enables the {@code "proxy-revalidate"} directive.
     */
    public ServerCacheControlBuilder proxyRevalidate() {
        return proxyRevalidate(true);
    }

    /**
     * Enables or disables the {@code "proxy-revalidate"} directive.
     *
     * @param proxyRevalidate {@code true} to enable or {@code false} to disable.
     */
    public ServerCacheControlBuilder proxyRevalidate(boolean proxyRevalidate) {
        this.proxyRevalidate = proxyRevalidate;
        return this;
    }

    /**
     * Enables or disables the {@code "s-maxage"} directive.
     *
     * @param sMaxAge the value of the directive to enable, or {@code null} to disable.
     */
    public ServerCacheControlBuilder sMaxAge(@Nullable Duration sMaxAge) {
        sMaxAgeSeconds = validateDuration(sMaxAge, "sMaxAge");
        return this;
    }

    /**
     * Enables the {@code "s-maxage"} directive.
     *
     * @param sMaxAgeSeconds the value in seconds.
     */
    public ServerCacheControlBuilder sMaxAgeSeconds(long sMaxAgeSeconds) {
        this.sMaxAgeSeconds = validateSeconds(sMaxAgeSeconds, "sMaxAgeSeconds");
        return this;
    }

    /**
     * Returns a newly created {@link ServerCacheControl} with the directives enabled so far.
     */
    @Override
    public ServerCacheControl build() {
        return (ServerCacheControl) super.build();
    }

    @Override
    ServerCacheControl build(boolean noCache, boolean noStore, boolean noTransform, long maxAgeSeconds) {
        // 'public' and 'private' are mutually exclusive.
        final boolean cachePublic = cachePrivate ? false : this.cachePublic;
        return new ServerCacheControl(noCache, noStore, noTransform, maxAgeSeconds,
                                      cachePublic, cachePrivate, immutable,
                                      mustRevalidate, proxyRevalidate, sMaxAgeSeconds);
    }

    // Overridden to change the return type.

    @Override
    public ServerCacheControlBuilder noCache() {
        return (ServerCacheControlBuilder) super.noCache();
    }

    @Override
    public ServerCacheControlBuilder noCache(boolean noCache) {
        return (ServerCacheControlBuilder) super.noCache(noCache);
    }

    @Override
    public ServerCacheControlBuilder noStore() {
        return (ServerCacheControlBuilder) super.noStore();
    }

    @Override
    public ServerCacheControlBuilder noStore(boolean noStore) {
        return (ServerCacheControlBuilder) super.noStore(noStore);
    }

    @Override
    public ServerCacheControlBuilder noTransform() {
        return (ServerCacheControlBuilder) super.noTransform();
    }

    @Override
    public ServerCacheControlBuilder noTransform(boolean noTransform) {
        return (ServerCacheControlBuilder) super.noTransform(noTransform);
    }

    @Override
    public ServerCacheControlBuilder maxAge(@Nullable Duration maxAge) {
        return (ServerCacheControlBuilder) super.maxAge(maxAge);
    }

    @Override
    public ServerCacheControlBuilder maxAgeSeconds(long maxAgeSeconds) {
        return (ServerCacheControlBuilder) super.maxAgeSeconds(maxAgeSeconds);
    }
}
