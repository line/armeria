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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.concurrent.EventExecutor;

/**
 * A utility class for {@link StreamMessage}.
 */
@UnstableApi
public final class StreamMessages {

    /**
     * Write the specified {@link StreamMessage} to the given {@link Path} with {@link OpenOption}.
     * If the {@link OpenOption} is not specified, defaults to {@link StandardOpenOption#CREATE},
     * {@link StandardOpenOption#TRUNCATE_EXISTING} and {@link StandardOpenOption#WRITE}.
     *
     * @param publisher the source of {@link HttpData} to be written
     * @param destination the {@link Path} to write to
     * @param options the {@link OpenOption} specifying how the file is opened
     * @return a {@link CompletableFuture} that completes successfully when the {@link StreamMessage} is fully
     *         written to the {@link Path} or exceptionally an error occurred while writing the
     *         {@link StreamMessage}.
     */
    public static CompletableFuture<Void> writeTo(StreamMessage<? extends HttpData> publisher,
                                                  Path destination, OpenOption... options) {
        requireNonNull(publisher, "publisher");
        requireNonNull(destination, "destination");
        requireNonNull(options, "options");

        final RequestContext ctx = RequestContext.currentOrNull();
        EventExecutor eventExecutor = null;
        ExecutorService blockingTaskExecutor = null;
        if (ctx != null) {
            eventExecutor = ctx.eventLoop();
            if (ctx instanceof ServiceRequestContext) {
                blockingTaskExecutor = ((ServiceRequestContext) ctx).blockingTaskExecutor();
            }
        }
        if (eventExecutor == null) {
            eventExecutor = CommonPools.workerGroup().next();
        }
        if (blockingTaskExecutor == null) {
            blockingTaskExecutor = CommonPools.blockingTaskExecutor();
        }

        return writeTo(publisher, destination, eventExecutor, blockingTaskExecutor, options);
    }

    /**
     * Write the specified {@link  StreamMessage} to the given {@link Path} with {@link OpenOption}.
     * If the {@link OpenOption} is not specified, defaults to {@link StandardOpenOption#CREATE},
     * {@link StandardOpenOption#TRUNCATE_EXISTING} and {@link StandardOpenOption#WRITE}.
     *
     * @param publisher the source of {@link HttpData} to be written
     * @param destination the {@link Path} to write to
     * @param eventExecutor the {@link EventExecutor} to subscribe to the given publisher
     * @param blockingTaskExecutor the {@link ExecutorService} to which blocking tasks are submitted to handle
     *                             file I/O events and write operations
     * @param options the {@link OpenOption} specifying how the file is opened
     * @return a {@link CompletableFuture} that completes successfully when the {@link StreamMessage} is fully
     *         written to the {@link Path} or exceptionally an error occurred while writing the
     *         {@link StreamMessage}.
     */
    public static CompletableFuture<Void> writeTo(StreamMessage<? extends HttpData> publisher, Path destination,
                                                  EventExecutor eventExecutor,
                                                  ExecutorService blockingTaskExecutor, OpenOption... options) {
        requireNonNull(publisher, "publisher");
        requireNonNull(destination, "destination");
        requireNonNull(eventExecutor, "eventExecutor");
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        requireNonNull(options, "options");

        final Set<OpenOption> writeOptions = checkWriteOptions(options);
        return new AsyncFileWriter(publisher, destination, writeOptions, eventExecutor,
                                   blockingTaskExecutor).whenComplete();
    }

    private static Set<OpenOption> checkWriteOptions(OpenOption[] options) {
        final int length = options.length;
        final ImmutableSet.Builder<OpenOption> writeOptions =
                ImmutableSet.builderWithExpectedSize(length + 3);

        if (length == 0) {
            writeOptions.add(StandardOpenOption.CREATE);
            writeOptions.add(StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            for (OpenOption opt : options) {
                if (opt == StandardOpenOption.READ) {
                    throw new IllegalArgumentException("READ not allowed");
                }
                writeOptions.add(opt);
            }
        }
        writeOptions.add(StandardOpenOption.WRITE);
        return writeOptions.build();
    }

    private StreamMessages() {}
}
