/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.ServiceRequestContext;

final class FileAggregatedMultipart {
    private ListMultimap<String, String> params;
    private ListMultimap<String, Path> files;

    FileAggregatedMultipart(ListMultimap<String, String> params,
                            ListMultimap<String, Path> files) {
        this.params = params;
        this.files = files;
    }

    ListMultimap<String, String> params() {
        return params;
    }

    ListMultimap<String, Path> files() {
        return files;
    }

    static CompletableFuture<FileAggregatedMultipart> aggregateMultipart(ServiceRequestContext ctx,
                                                                         HttpRequest req) {
        final Path multipartLocation = ctx.config().server().config().multipartLocation();
        return Multipart.from(req).collect(bodyPart -> {
            if (bodyPart.filename() != null) {
                return resolveTmpFile(multipartLocation, ctx.blockingTaskExecutor().withoutContext())
                        .thenComposeAsync(
                                path -> bodyPart.writeTo(path)
                                                .thenApply(ignore -> Maps.<String, Object>immutableEntry(
                                                        bodyPart.name(), path)),
                                ctx.eventLoop());
            }
            return bodyPart.aggregate()
                           .thenApply(aggregatedBodyPart -> Maps.<String, Object>immutableEntry(
                                   bodyPart.name(), aggregatedBodyPart.contentUtf8()));
        }).thenApply(result -> {
            final ImmutableListMultimap.Builder<String, String> params = ImmutableListMultimap.builder();
            final ImmutableListMultimap.Builder<String, Path> files =
                    ImmutableListMultimap.builder();
            for (Entry<String, Object> entry : result) {
                final Object value = entry.getValue();
                if (value instanceof Path) {
                    files.put(entry.getKey(), (Path) value);
                } else {
                    params.put(entry.getKey(), (String) value);
                }
            }
            return new FileAggregatedMultipart(params.build(), files.build());
        });
    }

    private static CompletableFuture<Path> resolveTmpFile(Path directory,
                                                          ExecutorService blockingExecutorService) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                return Files.createTempFile(directory, null, ".multipart");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, blockingExecutorService);
    }
}
