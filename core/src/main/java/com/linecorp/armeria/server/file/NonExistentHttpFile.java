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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;

import io.netty.buffer.ByteBufAllocator;

final class NonExistentHttpFile implements HttpFile {

    static final NonExistentHttpFile INSTANCE = new NonExistentHttpFile();

    private static final CompletableFuture<AggregatedHttpFile> AGGREGATED_FUTURE =
            UnmodifiableFuture.completedFuture(NonExistentAggregatedHttpFile.INSTANCE);

    private NonExistentHttpFile() {}

    @Override
    public CompletableFuture<@Nullable HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<@Nullable HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            switch (req.method()) {
                case HEAD:
                case GET:
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                default:
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }
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
