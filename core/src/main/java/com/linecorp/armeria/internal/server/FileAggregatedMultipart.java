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

package com.linecorp.armeria.internal.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.EventLoop;

public final class FileAggregatedMultipart {
    private static final Logger logger = LoggerFactory.getLogger(FileAggregatedMultipart.class);

    private final ListMultimap<String, String> params;
    private final ListMultimap<String, MultipartFile> files;

    private FileAggregatedMultipart(ListMultimap<String, String> params,
                                    ListMultimap<String, MultipartFile> files) {
        this.params = params;
        this.files = files;
    }

    public ListMultimap<String, String> params() {
        return params;
    }

    public ListMultimap<String, MultipartFile> files() {
        return files;
    }

    public static CompletableFuture<FileAggregatedMultipart> aggregateMultipart(ServiceRequestContext ctx,
                                                                                HttpRequest req) {
        final Path destination = ctx.config().multipartUploadsLocation();
        return Multipart.from(req).collect(bodyPart -> {
            final String name = bodyPart.name();
            assert name != null;
            final String filename = bodyPart.filename();
            final EventLoop eventLoop = ctx.eventLoop();

            if (filename != null) {
                final Path incompleteDir = destination.resolve("incomplete");
                final ScheduledExecutorService executor = ctx.blockingTaskExecutor().withoutContext();

                return resolveTmpFile(incompleteDir, filename, executor).thenCompose(path -> {
                    return bodyPart.writeTo(path, eventLoop, executor).thenCompose(ignore -> {
                        final Path completeDir = destination.resolve("complete");
                        return moveFile(path, completeDir, executor, ctx);
                    }).thenApply(completePath -> MultipartFile.of(name, filename, completePath.toFile(),
                                                                  bodyPart.headers()));
                });
            }

            return bodyPart.aggregateWithPooledObjects(eventLoop, ctx.alloc()).thenApply(aggregatedBodyPart -> {
                try (HttpData httpData = aggregatedBodyPart.content()) {
                    return Maps.<String, Object>immutableEntry(name, httpData.toStringUtf8());
                }
            });
        }).thenApply(results -> {
            final ImmutableListMultimap.Builder<String, String> params = ImmutableListMultimap.builder();
            final ImmutableListMultimap.Builder<String, MultipartFile> files =
                    ImmutableListMultimap.builder();
            for (Object result : results) {
                if (result instanceof MultipartFile) {
                    final MultipartFile multipartFile = (MultipartFile) result;
                    files.put(multipartFile.name(), multipartFile);
                } else {
                    @SuppressWarnings("unchecked")
                    final Entry<String, String> entry = (Entry<String, String>) result;
                    params.put(entry.getKey(), entry.getValue());
                }
            }
            return new FileAggregatedMultipart(params.build(), files.build());
        });
    }

    private static CompletableFuture<Path> moveFile(Path file, Path targetDirectory,
                                                    ExecutorService blockingExecutorService,
                                                    ServiceRequestContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(targetDirectory);
                // Avoid name duplication, create new file at target place and replace it.
                final Path tempFile = createRemovableTempFile(targetDirectory, blockingExecutorService, ctx);
                return Files.move(file, tempFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, blockingExecutorService);
    }

    private static Path createRemovableTempFile(Path targetDirectory,
                                                ExecutorService blockingExecutorService,
                                                ServiceRequestContext ctx) throws IOException {
        final Path tempFile = Files.createTempFile(targetDirectory, null, ".multipart");
        switch (ctx.config().multipartRemovalStrategy()) {
            case NEVER:
                break;
            case ON_RESPONSE_COMPLETION:
                ctx.log().whenComplete().thenAcceptAsync(unused -> {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException e) {
                        logger.warn("Failed to delete a temporary file: {}", tempFile, e);
                    }
                }, blockingExecutorService);
                break;
        }
        return tempFile;
    }

    private static CompletableFuture<Path> resolveTmpFile(Path directory,
                                                          String filename,
                                                          ExecutorService blockingExecutorService) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                return Files.createTempFile(directory, null, '-' + filename);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, blockingExecutorService);
    }
}
