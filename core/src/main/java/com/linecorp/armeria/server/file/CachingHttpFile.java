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
package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;

import io.netty.buffer.ByteBufAllocator;

final class CachingHttpFile implements HttpFile {

    private static final Logger logger = LoggerFactory.getLogger(CachingHttpFile.class);

    private final HttpFile file;
    private final int maxCachingLength;
    @Nullable
    private volatile AggregatedHttpFile cachedFile;

    CachingHttpFile(HttpFile file, int maxCachingLength) {
        this.file = requireNonNull(file, "file");
        this.maxCachingLength = maxCachingLength;
    }

    @Override
    public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return file.readAttributes(fileReadExecutor);
    }

    @Override
    public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return file.readHeaders(fileReadExecutor);
    }

    @Override
    public CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        return getFile(fileReadExecutor).read(fileReadExecutor, alloc);
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        return getFile(fileReadExecutor).aggregate(fileReadExecutor);
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                            ByteBufAllocator alloc) {
        return getFile(fileReadExecutor).aggregateWithPooledObjects(fileReadExecutor, alloc);
    }

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            try {
                return getFile(ctx.blockingTaskExecutor()).asService().serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        };
    }

    private HttpFile getFile(Executor fileReadExecutor) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        return HttpFile.from(file.readAttributes(fileReadExecutor).thenApply(uncachedAttrs -> {
            if (uncachedAttrs == null) {
                // Non-existent file. Invalidate the cache just in case it existed before.
                cachedFile = null;
                return HttpFile.nonExistent();
            }

            if (uncachedAttrs.length() > maxCachingLength) {
                // Invalidate the cache just in case the file was small previously.
                cachedFile = null;
                return file;
            }

            final AggregatedHttpFile cachedFile = this.cachedFile;
            if (cachedFile == null) {
                // Cache miss. Add a new entry to the cache.
                return cache(fileReadExecutor);
            }

            final HttpFileAttributes cachedAttrs = cachedFile.attributes();
            assert cachedAttrs != null;
            if (cachedAttrs.equals(uncachedAttrs)) {
                // Cache hit, and the cached file is up-to-date.
                return cachedFile.toHttpFile();
            }

            // Cache hit, but the cached file is out of date. Replace the old entry from the cache.
            this.cachedFile = null;
            return cache(fileReadExecutor);
        }));
    }

    private HttpFile cache(Executor fileReadExecutor) {
        return HttpFile.from(file.aggregate(fileReadExecutor).thenApply(aggregated -> {
            cachedFile = aggregated;
            return aggregated.toHttpFile();
        }).exceptionally(cause -> {
            logger.warn("Failed to cache a file: {}", file, Exceptions.peel(cause));
            return file;
        }));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("file", file)
                          .add("maxCachingLength", maxCachingLength)
                          .add("cachedFile", cachedFile)
                          .toString();
    }
}
