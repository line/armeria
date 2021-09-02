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

import java.io.File;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.base.Splitter;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.EncodingService;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;

/**
 * An {@link HttpService} that serves static files from a file system.
 *
 * @see FileServiceBuilder
 */
public final class FileService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    private static final Splitter COMMA_SPLITTER = Splitter.on(',');

    private static final UnmodifiableFuture<HttpFile> NON_EXISTENT_FILE_FUTURE =
            UnmodifiableFuture.completedFuture(HttpFile.nonExistent());

    /**
     * Returns a new {@link FileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static FileService of(File rootDir) {
        return builder(rootDir).build();
    }

    /**
     * Returns a new {@link FileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static FileService of(Path rootDir) {
        return builder(rootDir).build();
    }

    /**
     * Returns a new {@link FileService} for the specified {@code rootDir} in the current class path.
     */
    public static FileService of(ClassLoader classLoader, String rootDir) {
        return builder(classLoader, rootDir).build();
    }

    /**
     * Returns a new {@link FileService} for the specified {@link HttpVfs}.
     */
    public static FileService of(HttpVfs vfs) {
        return builder(vfs).build();
    }

    /**
     * Returns a new {@link FileServiceBuilder} with the specified {@code rootDir} in an O/S file system.
     */
    public static FileServiceBuilder builder(File rootDir) {
        return builder(HttpVfs.of(rootDir));
    }

    /**
     * Returns a new {@link FileServiceBuilder} with the specified {@code rootDir} in an O/S file system.
     */
    public static FileServiceBuilder builder(Path rootDir) {
        return builder(HttpVfs.of(rootDir));
    }

    /**
     * Returns a new {@link FileServiceBuilder} with the specified {@code rootDir} in the current class
     * path.
     */
    public static FileServiceBuilder builder(ClassLoader classLoader, String rootDir) {
        return builder(HttpVfs.of(classLoader, rootDir));
    }

    /**
     * Returns a new {@link FileServiceBuilder} with the specified {@link HttpVfs}.
     */
    public static FileServiceBuilder builder(HttpVfs vfs) {
        return new FileServiceBuilder(vfs);
    }

    private final FileServiceConfig config;

    @Nullable
    private final Cache<PathAndEncoding, AggregatedHttpFile> cache;

    FileService(FileServiceConfig config) {
        this.config = requireNonNull(config, "config");
        @Nullable
        final String cacheSpec = config.entryCacheSpec();
        if (cacheSpec != null) {
            cache = newCache(cacheSpec);
        } else {
            cache = null;
        }
    }

    private static Cache<PathAndEncoding, AggregatedHttpFile> newCache(String cacheSpec) {
        final Caffeine<Object, Object> b = Caffeine.from(cacheSpec);
        b.recordStats()
         .removalListener((RemovalListener<PathAndEncoding, AggregatedHttpFile>) (key, value, cause) -> {
             if (value != null) {
                 @Nullable
                 final HttpData data = value.content();
                 if (data != null) {
                     data.close();
                 }
             }
         });
        return b.build();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        final MeterRegistry registry = cfg.server().meterRegistry();
        if (cache != null) {
            final MeterIdPrefix meterIdPrefix =
                    new MeterIdPrefix("armeria.server.file.vfs.cache",
                                      "hostname.pattern",
                                      cfg.virtualHost().hostnamePattern(),
                                      "route", cfg.route().patternString(),
                                      "vfs", config.vfs().meterTag());

            CaffeineMetricSupport.setup(registry, meterIdPrefix, cache);
        }
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, Route route) {
        // We assume that if a file cache is enabled, the number of paths is also finite.
        return cache != null;
    }

    /**
     * Returns the configuration.
     */
    public FileServiceConfig config() {
        return config;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return findFile(ctx, req).asService().serve(ctx, req);
    }

    private HttpFile findFile(ServiceRequestContext ctx, HttpRequest req) {
        final String decodedMappedPath = ctx.decodedMappedPath();

        final EnumSet<FileServiceContentEncoding> supportedEncodings =
                EnumSet.noneOf(FileServiceContentEncoding.class);

        if (config.serveCompressedFiles()) {
            // We do a simple parse of the accept-encoding header, without worrying about star values
            // or priorities.
            @Nullable
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

        return HttpFile.from(findFile(ctx, decodedMappedPath, supportedEncodings).thenCompose(file -> {
            if (file != null) {
                return UnmodifiableFuture.completedFuture(file);
            }

            final boolean endsWithSlash = decodedMappedPath.charAt(decodedMappedPath.length() - 1) == '/';
            if (endsWithSlash) {
                // Try index.html if it was a directory access.
                final String indexPath = decodedMappedPath + "index.html";
                return findFile(ctx, indexPath, supportedEncodings).thenCompose(indexFile -> {
                    if (indexFile != null) {
                        return UnmodifiableFuture.completedFuture(indexFile);
                    }

                    // Auto-generate directory listing if enabled.
                    final Executor fileReadExecutor = ctx.blockingTaskExecutor();
                    if (!config.autoIndex()) {
                        return NON_EXISTENT_FILE_FUTURE;
                    }

                    return config.vfs().canList(fileReadExecutor, decodedMappedPath).thenCompose(canList -> {
                        if (!canList) {
                            return NON_EXISTENT_FILE_FUTURE;
                        }

                        return config.vfs().list(fileReadExecutor, decodedMappedPath).thenApply(listing -> {
                            final HttpData autoIndex =
                                    AutoIndex.listingToHtml(ctx.decodedPath(), decodedMappedPath, listing);
                            return HttpFile.builder(autoIndex)
                                           .addHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.HTML_UTF_8)
                                           .setHeaders(config.headers())
                                           .build();
                        });
                    });
                });
            } else {
                // Redirect to the slash appended path if:
                // 1) /index.html exists or
                // 2) it has a directory listing.
                final String indexPath = decodedMappedPath + "/index.html";
                return findFile(ctx, indexPath, supportedEncodings).thenCompose(indexFile -> {
                    if (indexFile != null) {
                        return UnmodifiableFuture.completedFuture(true);
                    }

                    if (!config.autoIndex()) {
                        return UnmodifiableFuture.completedFuture(false);
                    }

                    return config.vfs().canList(ctx.blockingTaskExecutor(), decodedMappedPath);
                }).thenApply(canList -> {
                    if (canList) {
                        throw HttpResponseException.of(HttpResponse.of(
                                ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                   HttpHeaderNames.LOCATION, ctx.path() + '/')));
                    } else {
                        return HttpFile.nonExistent();
                    }
                });
            }
        }));
    }

    private CompletableFuture<@Nullable HttpFile> findFile(ServiceRequestContext ctx, String path,
                                                           Set<FileServiceContentEncoding> supportedEncodings) {
        return findFile(ctx, path, supportedEncodings.iterator()).thenCompose(file -> {
            if (file != null) {
                return UnmodifiableFuture.completedFuture(file);
            } else {
                return findFile(ctx, path, (String) null);
            }
        });
    }

    private CompletableFuture<@Nullable HttpFile> findFile(ServiceRequestContext ctx, String path,
                                                           Iterator<FileServiceContentEncoding> i) {
        if (!i.hasNext()) {
            return UnmodifiableFuture.completedFuture(null);
        }

        final FileServiceContentEncoding encoding = i.next();
        final String contentEncoding = encoding.headerValue;
        return findFile(ctx, path + encoding.extension, contentEncoding).thenCompose(file -> {
            if (file != null) {
                return UnmodifiableFuture.completedFuture(file);
            } else {
                return findFile(ctx, path, i);
            }
        });
    }

    private CompletableFuture<@Nullable HttpFile> findFile(ServiceRequestContext ctx, String path,
                                                           @Nullable String contentEncoding) {

        final ScheduledExecutorService fileReadExecutor = ctx.blockingTaskExecutor();
        final HttpFile uncachedFile = config.vfs().get(fileReadExecutor, path, config.clock(),
                                                       contentEncoding, config.headers());

        return uncachedFile.readAttributes(fileReadExecutor).thenApply(uncachedAttrs -> {
            if (cache == null) {
                return uncachedAttrs != null ? uncachedFile : null;
            }

            final PathAndEncoding pathAndEncoding = new PathAndEncoding(path, contentEncoding);
            if (uncachedAttrs == null) {
                // Non-existent file. Invalidate the cache just in case it existed before.
                cache.invalidate(pathAndEncoding);
                return null;
            }

            if (uncachedAttrs.length() > config.maxCacheEntrySizeBytes()) {
                // Invalidate the cache just in case the file was small previously.
                cache.invalidate(pathAndEncoding);
                return uncachedFile;
            }

            @Nullable
            final AggregatedHttpFile cachedFile = cache.getIfPresent(pathAndEncoding);
            if (cachedFile == null) {
                // Cache miss. Add a new entry to the cache.
                return cache(ctx, pathAndEncoding, uncachedFile);
            }

            final HttpFileAttributes cachedAttrs = cachedFile.attributes();
            assert cachedAttrs != null;
            if (cachedAttrs.equals(uncachedAttrs)) {
                // Cache hit, and the cached file is up-to-date.
                return cachedFile.toHttpFile();
            }

            // Cache hit, but the cached file is out of date. Replace the old entry from the cache.
            cache.invalidate(pathAndEncoding);
            return cache(ctx, pathAndEncoding, uncachedFile);
        });
    }

    private HttpFile cache(
            ServiceRequestContext ctx, PathAndEncoding pathAndEncoding, HttpFile uncachedFile) {

        assert cache != null;

        final Executor executor = ctx.blockingTaskExecutor();
        final ByteBufAllocator alloc = ctx.alloc();

        return HttpFile.from(uncachedFile.aggregateWithPooledObjects(executor, alloc).thenApply(aggregated -> {
            cache.put(pathAndEncoding, aggregated);
            return aggregated.toHttpFile();
        }).exceptionally(cause -> {
            logger.warn("{} Failed to cache a file: {}", ctx, uncachedFile, Exceptions.peel(cause));
            return uncachedFile;
        }));
    }

    /**
     * Creates a new {@link HttpService} that tries this {@link FileService} first and then the specified
     * {@link HttpService} when this {@link FileService} does not have a requested resource.
     *
     * @param nextService the {@link HttpService} to try secondly
     */
    public HttpService orElse(HttpService nextService) {
        requireNonNull(nextService, "nextService");
        return new OrElseHttpService(this, nextService);
    }

    private static final class OrElseHttpService implements HttpService {

        private final FileService first;
        private final HttpService second;

        OrElseHttpService(FileService first, HttpService second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            first.serviceAdded(cfg);
            second.serviceAdded(cfg);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(
                    first.findFile(ctx, req)
                         .readAttributes(ctx.blockingTaskExecutor())
                         .thenApply(firstAttrs -> {
                             try {
                                 if (firstAttrs != null) {
                                     return first.serve(ctx, req);
                                 }

                                 return second.serve(ctx, req);
                             } catch (Exception e) {
                                 return Exceptions.throwUnsafely(e);
                             }
                         }));
        }

        @Override
        public boolean shouldCachePath(String path, @Nullable String query, Route route) {
            // No good way of propagating the first vs second decision to the cache decision, so just make a
            // best effort, it should work for most cases.
            return first.shouldCachePath(path, query, route) &&
                   second.shouldCachePath(path, query, route);
        }
    }

    /**
     * Content encodings supported by {@link FileService}. Will generally support more formats than
     * {@link EncodingService} because new formats can be added as soon as browsers and build tools
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
        public boolean equals(@Nullable Object obj) {
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
