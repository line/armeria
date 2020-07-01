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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.buffer.ByteBufAllocator;

/**
 * An immutable variant of {@link HttpFile} which has its attributes and content readily available.
 * Unlike {@link HttpFile}, the following operations in {@link AggregatedHttpFile} neither blocks nor raises
 * an exception:
 * <ul>
 *   <li>{@link #readAttributes(Executor)}</li>
 *   <li>{@link #readHeaders(Executor)}</li>
 *   <li>{@link #read(Executor, ByteBufAllocator)}</li>
 *   <li>{@link #aggregate(Executor)}</li>
 *   <li>{@link #aggregateWithPooledObjects(Executor, ByteBufAllocator)}</li>
 * </ul>
 * It also has the following additional methods that give you an immediate access to the file:
 * <ul>
 *   <li>{@link #readAttributes()}</li>
 *   <li>{@link #readHeaders()}</li>
 *   <li>{@link #read()}</li>
 * </ul>
 */
public interface AggregatedHttpFile extends HttpFile {

    /**
     * Returns the attributes of the file.
     *
     * @return the attributes, or {@code null} if the file does not exist.
     */
    @Nullable
    HttpFileAttributes readAttributes();

    @Override
    default CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(readAttributes());
    }

    /**
     * Returns the attributes of this file as {@link ResponseHeaders}, which could be useful for building
     * a response for a {@code HEAD} request.
     *
     * @return the headers, or {@code null} if the file does not exist.
     */
    @Nullable
    ResponseHeaders readHeaders();

    @Override
    default CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(readHeaders());
    }

    /**
     * Returns the {@link AggregatedHttpResponse} generated from this file.
     *
     * @return the {@link AggregatedHttpResponse} of the file, or {@code null} if the file does not exist.
     */
    @Nullable
    default AggregatedHttpResponse read() {
        final ResponseHeaders headers = readHeaders();
        if (headers == null) {
            return null;
        } else {
            final HttpData content = content();
            assert content != null;
            return AggregatedHttpResponse.of(headers, content);
        }
    }

    @Override
    default CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        final AggregatedHttpResponse res = read();
        if (res == null) {
            return UnmodifiableFuture.completedFuture(null);
        } else {
            return UnmodifiableFuture.completedFuture(res.toHttpResponse());
        }
    }

    /**
     * Returns the content of the file.
     *
     * @return the content, or {@code null} if the file does not exist.
     */
    @Nullable
    HttpData content();

    @Override
    default CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    default CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                             ByteBufAllocator alloc) {
        return CompletableFuture.completedFuture(this);
    }
}
