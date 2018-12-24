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

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBufAllocator;

/**
 * An immutable variant of {@link HttpFile} which has its attributes and content readily available.
 * Unlike {@link HttpFile}, an {@link AggregatedHttpFile} does not raise an {@link IOException} for
 * <ul>
 *   <li>{@link #readAttributes()}</li>
 *   <li>{@link #readHeaders()}</li>
 *   <li>{@link #read(Executor, ByteBufAllocator)}</li>
 *   <li>{@link #aggregate(Executor)}</li>
 *   <li>{@link #aggregateWithPooledObjects(Executor, ByteBufAllocator)}</li>
 * </ul>
 * It also has an additional method {@link #content()} which gives you an immediate access to the file's
 * content.
 */
public interface AggregatedHttpFile extends HttpFile {

    /**
     * Returns the attributes of the file.
     *
     * @return the attributes, or {@code null} if the file does not exist.
     */
    @Nullable
    @Override
    HttpFileAttributes readAttributes();

    /**
     * Returns the attributes of this file as {@link HttpHeaders}, which could be useful for building
     * a response for a {@code HEAD} request.
     *
     * @return the headers, or {@code null} if the file does not exist.
     */
    @Nullable
    @Override
    HttpHeaders readHeaders();

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
