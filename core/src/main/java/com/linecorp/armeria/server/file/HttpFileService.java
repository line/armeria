/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.EnumSet;
import java.util.Objects;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Splitter;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.metric.CaffeineMetricSupport;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.server.file.HttpVfs.Entry;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * An {@link HttpService} that serves static files from a file system.
 *
 * @see HttpFileServiceBuilder
 */
public final class HttpFileService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(HttpFileService.class);

    private static final Splitter COMMA_SPLITTER = Splitter.on(',');

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileService forFileSystem(String rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileService forFileSystem(Path rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     */
    public static HttpFileService forClassPath(String rootDir) {
        return HttpFileServiceBuilder.forClassPath(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     */
    public static HttpFileService forClassPath(ClassLoader classLoader, String rootDir) {
        return HttpFileServiceBuilder.forClassPath(classLoader, rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@link HttpVfs}.
     */
    public static HttpFileService forVfs(HttpVfs vfs) {
        return HttpFileServiceBuilder.forVfs(vfs).build();
    }

    private final HttpFileServiceConfig config;

    @Nullable
    private final LoadingCache<PathAndEncoding, CachedEntry> cache;

    HttpFileService(HttpFileServiceConfig config) {
        this.config = requireNonNull(config, "config");

        if (config.maxCacheEntries() != 0) {
            cache = Caffeine.newBuilder()
                            .maximumSize(config.maxCacheEntries())
                            .recordStats()
                            .build(this::getEntryWithoutCache);
        } else {
            cache = null;
        }
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        final MeterRegistry registry = cfg.server().meterRegistry();
        if (cache != null) {
            CaffeineMetricSupport.setup(
                    registry,
                    new MeterIdPrefix("armeria.server.file.vfsCache",
                                      "hostnamePattern", cfg.virtualHost().hostnamePattern(),
                                      "pathMapping", cfg.pathMapping().meterTag(),
                                      "vfs", config.vfs().meterTag()),
                    cache);
        }
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, PathMapping pathMapping) {
        // We assume that if a file cache is enabled, the number of paths is also finite.
        return cache != null;
    }

    /**
     * Returns the configuration.
     */
    public HttpFileServiceConfig config() {
        return config;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        final Entry entry = getEntry(ctx, req);
        final long lastModifiedMillis = entry.lastModifiedMillis();

        if (lastModifiedMillis == 0) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }

        long ifModifiedSinceMillis = Long.MIN_VALUE;
        try {
            ifModifiedSinceMillis =
                    req.headers().getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, Long.MIN_VALUE);
        } catch (Exception e) {
            // Ignore the ParseException, which is raised on malformed date.
            //noinspection ConstantConditions
            if (!(e instanceof ParseException)) {
                throw e;
            }
        }

        // HTTP-date does not have subsecond-precision; add 999ms to it.
        if (ifModifiedSinceMillis > Long.MAX_VALUE - 999) {
            ifModifiedSinceMillis = Long.MAX_VALUE;
        } else {
            ifModifiedSinceMillis += 999;
        }

        if (lastModifiedMillis < ifModifiedSinceMillis) {
            return HttpResponse.of(
                    HttpHeaders.of(HttpStatus.NOT_MODIFIED)
                               .setTimeMillis(HttpHeaderNames.DATE, config().clock().millis())
                               .setTimeMillis(HttpHeaderNames.LAST_MODIFIED, lastModifiedMillis));
        }

        final HttpData data;
        try {
            data = entry.readContent();
        } catch (FileNotFoundException ignored) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            logger.warn("{} Unexpected exception reading a file:", ctx, e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        final HttpHeaders headers =
                HttpHeaders.of(HttpStatus.OK)
                           .setInt(HttpHeaderNames.CONTENT_LENGTH, data.length())
                           .setTimeMillis(HttpHeaderNames.DATE, config().clock().millis())
                           .setTimeMillis(HttpHeaderNames.LAST_MODIFIED, lastModifiedMillis);
        final MediaType mediaType = entry.mediaType();
        if (mediaType != null) {
            headers.contentType(mediaType);
        }
        final String contentEncoding = entry.contentEncoding();
        if (contentEncoding != null) {
            headers.set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        }

        return HttpResponse.of(headers, data);
    }

    private Entry getEntry(ServiceRequestContext ctx, HttpRequest req) {
        final String decodedMappedPath = ctx.decodedMappedPath();

        final EnumSet<FileServiceContentEncoding> supportedEncodings =
                EnumSet.noneOf(FileServiceContentEncoding.class);

        if (config.serveCompressedFiles()) {
            // We do a simple parse of the accept-encoding header, without worrying about star values
            // or priorities.
            final String acceptEncoding = req.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
            if (acceptEncoding != null) {
                for (String encoding : COMMA_SPLITTER.split(acceptEncoding)) {
                    for (FileServiceContentEncoding possibleEncoding : FileServiceContentEncoding.values()) {
                        if (encoding.contains(possibleEncoding.headerValue)) {
                            supportedEncodings.add(possibleEncoding);
                        }
                    }
                }
            }
        }

        final Entry entry = getEntryWithSupportedEncodings(decodedMappedPath, supportedEncodings);

        if (entry.lastModifiedMillis() == 0) {
            if (decodedMappedPath.charAt(decodedMappedPath.length() - 1) == '/') {
                // Try index.html if it was a directory access.
                final Entry indexEntry = getEntryWithSupportedEncodings(
                        decodedMappedPath + "index.html", supportedEncodings);
                if (indexEntry.lastModifiedMillis() != 0) {
                    return indexEntry;
                }
            }
        }

        return entry;
    }

    private Entry getEntry(String path, @Nullable String contentEncoding) {
        if (cache == null) {
            return config.vfs().get(path, contentEncoding);
        }

        final CachedEntry entry = cache.get(new PathAndEncoding(path, contentEncoding));
        assert entry != null; // Non-existent entry will have lastModifiedMillis of 0.
        return entry;
    }

    private CachedEntry getEntryWithoutCache(PathAndEncoding pathAndEncoding) {
        return new CachedEntry(config.vfs().get(pathAndEncoding.path, pathAndEncoding.contentEncoding),
                               config.maxCacheEntrySizeBytes());
    }

    private Entry getEntryWithSupportedEncodings(String path,
                                                 EnumSet<FileServiceContentEncoding> supportedEncodings) {
        for (FileServiceContentEncoding encoding : supportedEncodings) {
            final Entry entry = getEntry(path + encoding.extension, encoding.headerValue);
            if (entry.lastModifiedMillis() != 0) {
                return entry;
            }
        }
        return getEntry(path, null);
    }

    private static final class CachedEntry implements Entry {

        private final Entry entry;
        private final int maxCacheEntrySizeBytes;
        @Nullable
        private HttpData cachedContent;
        private volatile long cachedLastModifiedMillis;

        CachedEntry(Entry entry, int maxCacheEntrySizeBytes) {
            this.entry = entry;
            this.maxCacheEntrySizeBytes = maxCacheEntrySizeBytes;
            cachedLastModifiedMillis = entry.lastModifiedMillis();
        }

        @Override
        public MediaType mediaType() {
            return entry.mediaType();
        }

        @Nullable
        @Override
        public String contentEncoding() {
            return entry.contentEncoding();
        }

        @Override
        public long lastModifiedMillis() {
            final long newLastModifiedMillis = entry.lastModifiedMillis();
            if (newLastModifiedMillis != cachedLastModifiedMillis) {
                cachedLastModifiedMillis = newLastModifiedMillis;
                destroyContent();
            }

            return newLastModifiedMillis;
        }

        @Override
        public synchronized HttpData readContent() throws IOException {
            if (cachedContent == null) {
                final HttpData newContent = entry.readContent();
                if (newContent.length() > maxCacheEntrySizeBytes) {
                    // Do not cache if the content is too large.
                    return newContent;
                }
                cachedContent = newContent;
            }

            return cachedContent;
        }

        synchronized void destroyContent() {
            if (cachedContent != null) {
                cachedContent = null;
            }
        }

        @Override
        public String toString() {
            return entry.toString();
        }
    }

    /**
     * Creates a new {@link HttpService} that tries this {@link HttpFileService} first and then the specified
     * {@link HttpService} when this {@link HttpFileService} does not have a requested resource.
     *
     * @param nextService the {@link HttpService} to try secondly
     */
    public HttpService orElse(Service<HttpRequest, HttpResponse> nextService) {
        requireNonNull(nextService, "nextService");
        return new OrElseHttpService(this, nextService);
    }

    private static final class OrElseHttpService extends AbstractHttpService {

        private final HttpFileService first;
        private final Service<HttpRequest, HttpResponse> second;

        OrElseHttpService(HttpFileService first, Service<HttpRequest, HttpResponse> second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            first.serviceAdded(cfg);
            second.serviceAdded(cfg);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final Entry firstEntry = first.getEntry(ctx, req);
            if (firstEntry.lastModifiedMillis() != 0) {
                return first.serve(ctx, req);
            } else {
                return second.serve(ctx, req);
            }
        }

        @Override
        public boolean shouldCachePath(String path, @Nullable String query, PathMapping pathMapping) {
            // No good way of propagating the first vs second decision to the cache decision, so just make a
            // best effort, it should work for most cases.
            return first.shouldCachePath(path, query, pathMapping) &&
                   second.shouldCachePath(path, query, pathMapping);
        }
    }

    /**
     * Content encodings supported by {@link HttpFileService}. Will generally support more formats than
     * {@link HttpEncodingService} because new formats can be added as soon as browsers and build tools
     * support them, without having to implement on-the-fly compression.
     */
    private enum FileServiceContentEncoding {
        // Order matters, we use the enum ordinal as the priority to pick an encoding in. Encodings should
        // be ordered by priority.
        BROTLI(".br", "br"),
        GZIP(".gz", "gzip");

        private final String extension;
        private final String headerValue;

        FileServiceContentEncoding(String extension, String headerValue) {
            this.extension = extension;
            this.headerValue = headerValue;
        }
    }

    private static final class PathAndEncoding {
        private final String path;
        @Nullable
        private final String contentEncoding;

        PathAndEncoding(String path, @Nullable String contentEncoding) {
            this.path = path;
            this.contentEncoding = contentEncoding;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PathAndEncoding)) {
                return false;
            }
            return path.equals(((PathAndEncoding) obj).path) &&
                   Objects.equals(contentEncoding, ((PathAndEncoding) obj).contentEncoding);
        }

        @Override
        public int hashCode() {
            return path.hashCode() * 31 + Objects.hashCode(contentEncoding);
        }
    }
}
