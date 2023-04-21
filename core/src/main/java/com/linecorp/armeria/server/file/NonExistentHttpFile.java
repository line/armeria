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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;

import io.netty.buffer.ByteBufAllocator;

final class NonExistentHttpFile implements HttpFile {

    static final NonExistentHttpFile INSTANCE = new NonExistentHttpFile(null, false);

    private static final CompletableFuture<AggregatedHttpFile> AGGREGATED_FUTURE =
            UnmodifiableFuture.completedFuture(NonExistentAggregatedHttpFile.INSTANCE);

    @Nullable
    private final String location;
    private final boolean isRedirect;

    NonExistentHttpFile(@Nullable String location, boolean isRedirect) {
        this.location = location;
        this.isRedirect = isRedirect;
    }

    @Override
    public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            final HttpMethod method = req.method();
            if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }

            if (location != null && isRedirect) {
                return HttpResponse.ofRedirect(location);
            }

            if (location != null){
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                    String.format("No file are available for the location. location is %s ", location));
            }

            return HttpResponse.of(HttpStatus.NOT_FOUND);
        };
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        return AGGREGATED_FUTURE;
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                            ByteBufAllocator alloc) {
        return AGGREGATED_FUTURE;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
