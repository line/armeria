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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimaps;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.BodyParts.CollectedBodyParts;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * Handling multipart/form-data by saving the uploaded file to the specified path and
 * others in the {@link QueryParams}.
 */
final class ContentAwareMultipartCollector implements Subscriber<BodyPart> {
    private final CompletableFuture<CollectedBodyParts> future = new CompletableFuture<>();
    private final QueryParamsBuilder queryParamsBuilder = QueryParams.builder();
    private final ImmutableListMultimap.Builder<String, Path> files = new ImmutableListMultimap.Builder<>();
    @Nullable
    private Subscription subscription;
    private int inProgressCount;
    private boolean canComplete;

    private final Function<@Nullable String, Path> mappingFileName;
    private final OpenOption[] options;
    private final EventExecutor eventExecutor;
    private final ExecutorService blockingTaskExecutor;

    ContentAwareMultipartCollector(StreamMessage<? extends BodyPart> publisher,
                                   Function<@Nullable String, Path> mappingFileName,
                                   OpenOption[] options,
                                   EventExecutor eventExecutor, ExecutorService blockingTaskExecutor) {
        this.mappingFileName = mappingFileName;
        this.options = options;
        this.eventExecutor = eventExecutor;
        this.blockingTaskExecutor = blockingTaskExecutor;
        publisher.subscribe(this, eventExecutor, SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    public CompletableFuture<CollectedBodyParts> future() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        this.subscription = subscription;
        this.subscription.request(1);
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        assert subscription != null;
        @Nullable
        final String name = bodyPart.name();
        if (name == null) {
            // Content must be fully exhausted or consumed to trigger next BodyPart successfully
            bodyPart.content().abort();
            subscription.request(1);
            return;
        }

        inProgressCount++;
        if (bodyPart.filename() == null) {
            bodyPart.aggregateWithPooledObjects(eventExecutor, ByteBufAllocator.DEFAULT)
                    .whenCompleteAsync((aggregatedBodyPart, throwable) -> {
                        inProgressCount--;
                        try (HttpData content = aggregatedBodyPart.content()) {
                            if (throwable != null) {
                                subscription.cancel();
                                future.completeExceptionally(throwable);
                                return;
                            }
                            @Nullable
                            final MediaType mediaType = aggregatedBodyPart.contentType();
                            final Charset charset = mediaType == null ? StandardCharsets.UTF_8
                                                                      : mediaType.charset(
                                    StandardCharsets.UTF_8);
                            queryParamsBuilder.add(name, content.toString(charset));
                            subscription.request(1);
                            if (canComplete) {
                                doComplete();
                            }
                        }
                    }, eventExecutor);
            return;
        }

        final Path path = mappingFileName.apply(name);
        files.put(name, path);
        bodyPart.writeFile(path, eventExecutor, blockingTaskExecutor, options)
                .handleAsync((unused, throwable) -> {
                    inProgressCount--;
                    if (throwable != null) {
                        subscription.cancel();
                        future.completeExceptionally(throwable);
                        return null;
                    }
                    if (canComplete) {
                        doComplete();
                    } else {
                        subscription.request(1);
                    }
                    return null;
                }, eventExecutor);
    }

    @Override
    public void onError(Throwable t) {
        future.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        canComplete = true;
        doComplete();
    }

    private void doComplete() {
        // The last BodyPart is in-progress.
        if (inProgressCount != 0) {
            return;
        }
        if (future.isDone()) {
            return;
        }
        future.complete(new CollectedBodyParts(queryParamsBuilder.build(), Multimaps.asMap(files.build())));
    }
}
