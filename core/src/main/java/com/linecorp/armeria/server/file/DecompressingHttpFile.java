/*
 * Copyright 2021 LINE Corporation
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
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.encoding.StaticHttpDecodedResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.file.FileService.ContentEncoding;

import io.netty.buffer.ByteBufAllocator;

/**
 * An {@link HttpFile} that decompress a compressed {@link HttpFile} while being served.
 */
final class DecompressingHttpFile implements HttpFile {

    private final HttpFile compressedFile;
    private final ContentEncoding encoding;
    @Nullable
    private final MediaType contentType;

    DecompressingHttpFile(HttpFile compressedFile, ContentEncoding encoding, @Nullable MediaType contentType) {
        this.compressedFile = compressedFile;
        this.encoding = encoding;
        this.contentType = contentType;
    }

    @Override
    public CompletableFuture<@Nullable HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return compressedFile.readAttributes(fileReadExecutor);
    }

    @Override
    public CompletableFuture<@Nullable ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return compressedFile.readHeaders(fileReadExecutor);
    }

    @Override
    public CompletableFuture<@Nullable HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        return compressedFile.read(fileReadExecutor, alloc);
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        return compressedFile.aggregate(fileReadExecutor);
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                            ByteBufAllocator alloc) {
        return compressedFile.aggregateWithPooledObjects(fileReadExecutor, alloc);
    }

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            final HttpResponse response = compressedFile.asService().serve(ctx, req);
            return new StaticHttpDecodedResponse(response, encoding.decoderFactory.newDecoder(ctx.alloc()),
                                                 contentType);
        };
    }
}
