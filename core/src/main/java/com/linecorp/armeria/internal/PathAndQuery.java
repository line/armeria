/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.metric.CaffeineMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A parser of the raw path and query components of an HTTP path. Performs validation and allows caching of
 * results.
 */
public final class PathAndQuery {

    /**
     * According to RFC 3986 section 3.3, path can contain a colon, except the first segment.
     *
     * <p>Should allow the asterisk character in the path, query, or fragment components of a URL(RFC2396).
     * @see <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC 3986, section 3.3</a>
     */
    private static final Pattern PROHIBITED_PATH_PATTERN =
            Pattern.compile("^/[^/]*:[^/]*/|[|<>\\\\]|/\\.\\.|\\.\\.$|\\.\\./");

    private static final Pattern CONSECUTIVE_SLASHES_PATTERN = Pattern.compile("/{2,}");

    @Nullable
    private static final Cache<String, PathAndQuery> CACHE =
            Flags.parsedPathCacheSpec().map(PathAndQuery::buildCache).orElse(null);

    private static Cache<String, PathAndQuery> buildCache(String spec) {
        return Caffeine.from(spec).build();
    }

    public static void registerMetrics(MeterRegistry registry, MeterIdPrefix idPrefix) {
        if (CACHE != null) {
            CaffeineMetricSupport.setup(registry, idPrefix, CACHE);
        }
    }

    /**
     * Clears the currently cached parsed paths. Only for use in tests.
     */
    @VisibleForTesting
    public static void clearCachedPaths() {
        requireNonNull(CACHE, "CACHE");
        CACHE.asMap().clear();
    }

    /**
     * Returns paths that have had their parse result cached. Only for use in tests.
     */
    @VisibleForTesting
    public static Set<String> cachedPaths() {
        requireNonNull(CACHE, "CACHE");
        return CACHE.asMap().keySet();
    }

    /**
     * Validates the {@link String} that contains an absolute path and a query, and splits them into
     * the path part and the query part. If the path is usable (e.g., can be served a successful response from
     * the server and doesn't have variable path parameters), {@link PathAndQuery#storeInCache(String)} should
     * be called to cache the parsing result for faster future invocations.
     *
     * @return a {@link PathAndQuery} with the absolute path and query, or {@code null} if the specified
     *         {@link String} is not an absolute path or invalid.
     */
    @Nullable
    public static PathAndQuery parse(String rawPath) {
        if (CACHE != null) {
            final PathAndQuery parsed = CACHE.getIfPresent(rawPath);
            if (parsed != null) {
                return parsed;
            }
        }
        return splitPathAndQuery(rawPath);
    }

    /**
     * Stores this {@link PathAndQuery} into cache for the given raw path. This should be used by callers when
     * the parsed result was valid (e.g., when a server is able to successfully handle the parsed path).
     */
    public void storeInCache(String rawPath) {
        if (CACHE != null) {
            CACHE.put(rawPath, this);
        }
    }

    private final String path;
    @Nullable
    private final String query;

    private PathAndQuery(String path, @Nullable String query) {
        this.path = path;
        this.query = query;
    }

    public String path() {
        return path;
    }

    @Nullable
    public String query() {
        return query;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PathAndQuery)) {
            return false;
        }

        final PathAndQuery that = (PathAndQuery) o;
        return Objects.equals(path, that.path) &&
               Objects.equals(query, that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, query);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("path", path)
                .add("query", query)
                .toString();
    }

    @Nullable
    private static PathAndQuery splitPathAndQuery(final String pathAndQuery) {
        final String path;
        final String query;

        if (Strings.isNullOrEmpty(pathAndQuery)) {
            // e.g. http://example.com
            path = "/";
            query = null;
        } else if (pathAndQuery.charAt(0) != '/') {
            // Do not accept a relative path.
            return null;
        } else {
            // Split by the first '?'.
            final int queryPos = pathAndQuery.indexOf('?');
            if (queryPos >= 0) {
                path = pathAndQuery.substring(0, queryPos);
                query = pathAndQuery.substring(queryPos + 1);
            } else {
                path = pathAndQuery;
                query = null;
            }
        }

        // Make sure the path and the query are encoded correctly. i.e. Do not pass poorly encoded paths
        // and queries to services. However, do not pass the decoded paths and queries to the services,
        // so that users have more control over the encoding.
        if (!isValidEncoding(path) ||
            !isValidEncoding(query)) {
            return null;
        }

        // Reject the prohibited patterns.
        if (PROHIBITED_PATH_PATTERN.matcher(path).find()) {
            return null;
        }

        // Work around the case where a client sends a path such as '/path//with///consecutive////slashes'.
        return new PathAndQuery(CONSECUTIVE_SLASHES_PATTERN.matcher(path).replaceAll("/"), query);
    }

    @SuppressWarnings("DuplicateBooleanBranch")
    private static boolean isValidEncoding(@Nullable String value) {
        if (value == null) {
            return true;
        }

        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char ch = value.charAt(i);
            if (ch != '%') {
                continue;
            }

            final int end = i + 3;
            if (end > length) {
                // '%' or '%x' (must be followed by two hexadigits)
                return false;
            }

            if (!isHexadigit(value.charAt(++i)) ||
                !isHexadigit(value.charAt(++i))) {
                // The first or second digit is not hexadecimal.
                return false;
            }
        }

        return true;
    }

    private static boolean isHexadigit(char ch) {
        return ch >= '0' && ch <= '9' ||
               ch >= 'a' && ch <= 'f' ||
               ch >= 'A' && ch <= 'F';
    }
}
