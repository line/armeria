/*
 * Copyright 2020 LINE Corporation
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
import java.util.concurrent.CompletionStage;
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

final class DeferredHttpFile implements HttpFile {

    private static final Logger logger = LoggerFactory.getLogger(DeferredHttpFile.class);

    private static boolean warnedNullDelegate;

    private final CompletableFuture<? extends HttpFile> stage;
    @Nullable
    private volatile HttpFile delegate;

    DeferredHttpFile(CompletionStage<? extends HttpFile> stage) {
        this.stage = requireNonNull(stage, "stage").toCompletableFuture();
        if (this.stage.isDone() && !this.stage.isCompletedExceptionally()) {
            setDelegate(this.stage.getNow(null));
        }
    }

    @Override
    public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");

        final HttpFile delegate = this.delegate;
        if (delegate != null) {
            return delegate.readAttributes(fileReadExecutor);
        }

        return stage.thenCompose(file -> {
            setDelegate(file);
            return file.readAttributes(fileReadExecutor);
        });
    }

    @Override
    public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");

        final HttpFile delegate = this.delegate;
        if (delegate != null) {
            return delegate.readHeaders(fileReadExecutor);
        }

        return stage.thenCompose(file -> {
            setDelegate(file);
            return file.readHeaders(fileReadExecutor);
        });
    }

    @Override
    public CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(alloc, "alloc");

        final HttpFile delegate = this.delegate;
        if (delegate != null) {
            return delegate.read(fileReadExecutor, alloc);
        }

        return stage.thenCompose(file -> {
            setDelegate(file);
            return file.read(fileReadExecutor, alloc);
        });
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");

        final HttpFile delegate = this.delegate;
        if (delegate != null) {
            return delegate.aggregate(fileReadExecutor);
        }

        return stage.thenCompose(file -> {
            setDelegate(file);
            return file.aggregate(fileReadExecutor);
        });
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                            ByteBufAllocator alloc) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(alloc, "alloc");

        final HttpFile delegate = this.delegate;
        if (delegate != null) {
            return delegate.aggregateWithPooledObjects(fileReadExecutor, alloc);
        }

        return stage.thenCompose(file -> {
            setDelegate(file);
            return file.aggregateWithPooledObjects(fileReadExecutor, alloc);
        });
    }

    @Override
    public HttpService asService() {
        final HttpFile delegate = this.delegate;
        if (delegate != null) {
            return delegate.asService();
        }

        return (ctx, req) -> HttpResponse.of(stage.thenApply(file -> {
            setDelegate(file);
            try {
                return file.asService().serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    private void setDelegate(@Nullable HttpFile delegate) {
        if (delegate == null) {
            if (!warnedNullDelegate) {
                warnedNullDelegate = true;
                logger.warn("The delegate stage produced a null file; treating as a non-existent file.");
            }
            delegate = HttpFile.nonExistent();
        }
        this.delegate = delegate;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("delegate", delegate)
                          .toString();
    }
}
