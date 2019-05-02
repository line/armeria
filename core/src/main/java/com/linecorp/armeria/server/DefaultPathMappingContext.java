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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

/**
 * Holds the parameters which are required to find a service available to handle the request.
 */
final class DefaultPathMappingContext implements PathMappingContext {
    private static final Logger logger = LoggerFactory.getLogger(DefaultPathMappingContext.class);

    static final List<MediaType> ANY_TYPE = ImmutableList.of(MediaType.ANY_TYPE);

    private static final Splitter ACCEPT_SPLITTER = Splitter.on(',').trimResults();

    private final VirtualHost virtualHost;
    private final String hostname;
    private final HttpMethod method;
    private final String path;
    @Nullable
    private final String query;
    @Nullable
    private final MediaType contentType;
    private final List<MediaType> acceptTypes;
    private final boolean isCorsPreflight;
    private final List<Object> summary;
    @Nullable
    private Throwable delayedCause;

    DefaultPathMappingContext(VirtualHost virtualHost, String hostname,
                              HttpMethod method, String path, @Nullable String query,
                              @Nullable MediaType contentType, List<MediaType> acceptTypes,
                              boolean isCorsPreflight) {
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
        this.hostname = requireNonNull(hostname, "hostname");
        this.method = requireNonNull(method, "method");
        this.path = requireNonNull(path, "path");
        this.query = query;
        this.contentType = contentType;
        this.acceptTypes = requireNonNull(acceptTypes, "acceptTypes");
        this.isCorsPreflight = isCorsPreflight;
        summary = generateSummary(this);
    }

    @Override
    public VirtualHost virtualHost() {
        return virtualHost;
    }

    @Override
    public String hostname() {
        return hostname;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Nullable
    @Override
    public String query() {
        return query;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public List<MediaType> acceptTypes() {
        return acceptTypes;
    }

    @Override
    public boolean isCorsPreflight() {
        return isCorsPreflight;
    }

    @Override
    public List<Object> summary() {
        return summary;
    }

    @Override
    public void delayThrowable(Throwable delayedCause) {
        // Update with the last cause
        this.delayedCause = requireNonNull(delayedCause, "delayedCause");
    }

    @Override
    public Optional<Throwable> delayedThrowable() {
        return Optional.ofNullable(delayedCause);
    }

    @Override
    public int hashCode() {
        return summary().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DefaultPathMappingContext &&
               (this == obj || summary().equals(((DefaultPathMappingContext) obj).summary()));
    }

    @Override
    public String toString() {
        return summary().toString();
    }

    /**
     * Returns a new {@link PathMappingContext} instance.
     */
    static PathMappingContext of(VirtualHost virtualHost, String hostname,
                                 String path, @Nullable String query, HttpHeaders headers) {
        return of(virtualHost, hostname, path, query, headers, false);
    }

    /**
     * Returns a new {@link PathMappingContext} instance.
     */
    static PathMappingContext of(VirtualHost virtualHost, String hostname,
                                 String path, @Nullable String query,
                                 HttpHeaders headers, boolean isCorsPreflight) {
        return new DefaultPathMappingContext(virtualHost, hostname, headers.method(), path, query,
                                             headers.contentType(), extractAcceptTypes(headers),
                                             isCorsPreflight);
    }

    @VisibleForTesting
    static List<MediaType> extractAcceptTypes(HttpHeaders headers) {
        final List<String> acceptHeaders = headers.getAll(HttpHeaderNames.ACCEPT);
        if (acceptHeaders == null || acceptHeaders.isEmpty()) {
            // No 'Accept' header means accepting everything.
            return ANY_TYPE;
        }

        final List<MediaType> acceptTypes = new ArrayList<>(4);
        acceptHeaders.forEach(
                acceptHeader -> Streams.stream(ACCEPT_SPLITTER.split(acceptHeader)).forEach(
                        mediaType -> {
                            try {
                                acceptTypes.add(MediaType.parse(mediaType));
                            } catch (IllegalArgumentException e) {
                                logger.debug("Failed to parse the media type in 'accept' header: {}",
                                             mediaType, e);
                            }
                        }));
        if (acceptTypes.isEmpty()) {
            return ANY_TYPE;
        }

        if (acceptTypes.size() > 1) {
            acceptTypes.sort(DefaultPathMappingContext::compareMediaType);
        }
        return ImmutableList.copyOf(acceptTypes);
    }

    @VisibleForTesting
    static int compareMediaType(MediaType m1, MediaType m2) {
        // The order should be "q=1.0, q=0.5".
        // To ensure descending order, we pass the q values of m2 and m1 respectively.
        final int qCompare = Float.compare(m2.qualityFactor(), m1.qualityFactor());
        if (qCompare != 0) {
            return qCompare;
        }
        // The order should be "application/*, */*".
        final int wildcardCompare = Integer.compare(m1.numWildcards(), m2.numWildcards());
        if (wildcardCompare != 0) {
            return wildcardCompare;
        }
        // Finally, sort by lexicographic order. ex, application/*, image/*
        return m1.type().compareTo(m2.type());
    }

    /**
     * Returns a summary string of the given {@link PathMappingContext}.
     */
    static List<Object> generateSummary(PathMappingContext mappingCtx) {
        requireNonNull(mappingCtx, "mappingCtx");

        // 0 : VirtualHost
        // 1 : HttpMethod
        // 2 : Path
        // 3 : Content-Type
        // 4 : Accept
        final List<Object> summary = new ArrayList<>(8);

        summary.add(mappingCtx.virtualHost());
        summary.add(mappingCtx.method());
        summary.add(mappingCtx.path());
        summary.add(mappingCtx.contentType());

        final List<MediaType> acceptTypes = mappingCtx.acceptTypes();
        if (!acceptTypes.isEmpty()) {
            summary.addAll(acceptTypes);
        }
        return summary;
    }
}
