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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.MoreExecutors;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
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
        this.file = file;
        this.maxCachingLength = maxCachingLength;
    }

    @Nullable
    @Override
    public HttpFileAttributes readAttributes() throws IOException {
        return file.readAttributes();
    }

    @Nullable
    @Override
    public HttpHeaders readHeaders() throws IOException {
        return file.readHeaders();
    }

    @Nullable
    @Override
    public HttpResponse read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        try {
            final HttpFile file = getFile();
            return file != null ? file.read(fileReadExecutor, alloc) : null;
        } catch (Exception e) {
            return HttpResponse.ofFailure(e);
        }
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        try {
            final HttpFile file = getFile();
            return file != null ? file.aggregate(fileReadExecutor)
                                : CompletableFuture.completedFuture(HttpFile.nonExistent());
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                            ByteBufAllocator alloc) {
        try {
            final HttpFile file = getFile();
            return file != null ? file.aggregateWithPooledObjects(fileReadExecutor, alloc)
                                : CompletableFuture.completedFuture(HttpFile.nonExistent());
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }
    }

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            final HttpFile file = MoreObjects.firstNonNull(getFile(), HttpFile.nonExistent());
            return file.asService().serve(ctx, req);
        };
    }

    @Nullable
    private HttpFile getFile() throws IOException {
        final HttpFileAttributes uncachedAttrs = file.readAttributes();
        if (uncachedAttrs == null) {
            // Non-existent file. Invalidate the cache just in case it existed before.
            cachedFile = null;
            return null;
        }

        if (uncachedAttrs.length() > maxCachingLength) {
            // Invalidate the cache just in case the file was small previously.
            cachedFile = null;
            return file;
        }

        final AggregatedHttpFile cachedFile = this.cachedFile;
        if (cachedFile == null) {
            // Cache miss. Add a new entry to the cache.
            return cache();
        }

        final HttpFileAttributes cachedAttrs = cachedFile.readAttributes();
        assert cachedAttrs != null;
        if (cachedAttrs.equals(uncachedAttrs)) {
            // Cache hit, and the cached file is up-to-date.
            return cachedFile;
        }

        // Cache hit, but the cached file is out of date. Replace the old entry from the cache.
        this.cachedFile = null;
        return cache();
    }

    private HttpFile cache() {
        // TODO(trustin): We assume here that the file being read is small enough that it will not block
        //                an event loop for a long time. Revisit if the assumption turns out to be false.
        AggregatedHttpFile cachedFile = null;
        try {
            this.cachedFile = cachedFile = file.aggregate(MoreExecutors.directExecutor()).get();
        } catch (Exception e) {
            this.cachedFile = null;
            logger.warn("Failed to cache a file: {}", file, Exceptions.peel(e));
        }

        return MoreObjects.firstNonNull(cachedFile, file);
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
