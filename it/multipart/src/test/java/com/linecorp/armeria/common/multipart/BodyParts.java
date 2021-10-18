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
 * under the Licenses
 */

package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessages;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.concurrent.EventExecutor;

/**
 * A utility class for saving upload files and getting query parameters from {@link Multipart}.
 */
@UnstableApi
public class BodyParts {
    /**
     *  Immutable HTTP Multipart result.
     */
    public static class CollectedBodyParts {
        private final QueryParams queryParams;
        private final Map<String, List<Path>> files;

        CollectedBodyParts(QueryParams queryParams, Map<String, List<Path>> files) {
            this.queryParams = queryParams;
            this.files = files;
        }

        /**
         * Return whole {@link QueryParams} from the Multipart.
         */
        public QueryParams queryParams() {
            return queryParams;
        }

        /**
         * Returns a {@link Map} whose key is the name from {@link BodyPart} and value is {@link List} of
         * {@link Path} where to find uploaded files.
         */
        public Map<String, List<Path>> files() {
            return files;
        }
    }

    /**
     * Collect the query params and write the uploaded file to the given {@link Path} with
     * {@link OpenOption} from the specified {@link Multipart}.
     * If the {@link OpenOption} is not specified, defaults to {@link StandardOpenOption#CREATE},
     * {@link StandardOpenOption#TRUNCATE_EXISTING} and {@link StandardOpenOption#WRITE}.
     *
     * @param multipart the source of {@link Multipart} to be processed
     * @param mappingFileName the {@link Function} that generates a {@link Path} from the name in the part
     * @param options the {@link OpenOption} specifying how the file is opened
     * @return a {@link CompletableFuture} that completes with the aggregated {@link CollectedBodyParts} or an
     *         error
     *
     * @see StreamMessages
     */
    public static CompletableFuture<CollectedBodyParts> collect(
            Multipart multipart,
            Function<@Nullable String, Path> mappingFileName,
            OpenOption... options) {
        requireNonNull(multipart, "multipart");
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
        return new ContentAwareMultipartCollector(multipart.bodyParts(), mappingFileName, options, eventExecutor,
                                                  blockingTaskExecutor).future();
    }
}
