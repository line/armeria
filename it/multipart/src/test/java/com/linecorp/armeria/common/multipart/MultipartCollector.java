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

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.HttpObjectAggregator;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;

public class MultipartCollector implements Subscriber<BodyPart> {
    private final CompletableFuture<CustomAggregatedMultipart> future = new CompletableFuture<>();
    private final QueryParamsBuilder queryParamsBuilder = QueryParams.builder();
    private final Map<String, List<Path>> files = new HashMap<>();
    @Nullable
    private Subscription multipartSubscription;
    @Nullable
    private EventLoop eventLoop;
    private int inProgressCount;

    private boolean canComplete;

    private static CompletableFuture<Path> toPath(BodyPart bodyPart, Path path, EventLoop eventLoop) {
        final CompletableFuture<Path> future = new CompletableFuture<>();
        bodyPart.content().subscribe(new Subscriber<HttpData>() {
            @Nullable
            private Subscription bodyPartSubscription;
            @Nullable
            private AsynchronousFileChannel fileChannel;
            private long position;
            private boolean writing = true;
            private boolean closing;

            @Override
            public void onSubscribe(Subscription s) {
                bodyPartSubscription = s;
                try {
                    fileChannel = AsynchronousFileChannel.open(path, StandardOpenOption.WRITE);
                    bodyPartSubscription.request(1);
                } catch (IOException e) {
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onNext(HttpData data) {
                assert fileChannel != null;
                assert bodyPartSubscription != null;
                final ByteBuf byteBuf = data.byteBuf();
                fileChannel.write(
                        byteBuf.nioBuffer(), position, null,
                        new CompletionHandler<Integer, Void>() {
                            @Override
                            public void completed(Integer result, Void attachment) {
                                eventLoop.submit(() -> {
                                    position += result;
                                    writing = false;
                                    if (closing) {
                                        try {
                                            maybeCloseFileChannel();
                                            future.complete(path);
                                        } catch (IOException e) {
                                            bodyPartSubscription.cancel();
                                            future.completeExceptionally(e);
                                        }
                                    } else {
                                        bodyPartSubscription.request(1);
                                    }
                                });
                            }

                            @Override
                            public void failed(Throwable e, Void attachment) {
                                eventLoop.submit(() -> {
                                    writing = false;
                                    bodyPartSubscription.cancel();
                                    future.completeExceptionally(e);
                                });
                            }
                        });
            }

            @Override
            public void onError(Throwable t) {
                assert fileChannel != null;
                closing = true;
                if (writing) {
                    return;
                }
                try {
                    maybeCloseFileChannel();
                } catch (IOException e) {
                    t.addSuppressed(e);
                }
                future.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                assert fileChannel != null;
                closing = true;
                if (writing) {
                    return;
                }
                try {
                    maybeCloseFileChannel();
                    future.complete(path);
                } catch (IOException e) {
                    future.completeExceptionally(e);
                }
            }

            private void maybeCloseFileChannel() throws IOException {
                if (fileChannel.isOpen()) {
                    fileChannel.close();
                }
            }
        });
        return future;
    }

    public CompletableFuture<CustomAggregatedMultipart> future() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        multipartSubscription = s;
        eventLoop = RequestContext.mapCurrent(RequestContext::eventLoop,
                                              CommonPools.workerGroup()::next);
        multipartSubscription.request(1);
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        inProgressCount++;
        if (bodyPart.filename() == null) {
            final CompletableFuture<AggregatedBodyPart> future = new CompletableFuture<>();
            // Nested subscription need passing event-loop. Because the onNext is not context-aware.
            // Avoid it using common worker pool instead of event-loop from RequestContext.
            bodyPart.content().subscribe(
                    new HttpObjectAggregator<AggregatedBodyPart>(future, null) {
                        @Override
                        protected void onHeaders(HttpHeaders headers) {
                        }

                        @Override
                        protected AggregatedBodyPart onSuccess(HttpData content) {
                            return AggregatedBodyPart.of(bodyPart.headers(), content);
                        }

                        @Override
                        protected void onFailure() {
                        }
                    }, eventLoop);
            future.whenCompleteAsync((aggregatedBodyPart, throwable) -> {
                inProgressCount--;
                if (throwable != null) {
                    multipartSubscription.cancel();
                    future.completeExceptionally(throwable);
                    return;
                }
                @Nullable
                final String name = aggregatedBodyPart.name();
                if (name != null) {
                    @Nullable
                    final MediaType mediaType = aggregatedBodyPart.contentType();
                    final Charset charset = mediaType == null ? StandardCharsets.US_ASCII
                                                              : mediaType.charset(StandardCharsets.US_ASCII);
                    queryParamsBuilder.add(name, aggregatedBodyPart.content(charset));
                }
                multipartSubscription.request(1);
                if (canComplete) {
                    doComplete();
                }
            }, eventLoop);
            return;
        }
        try {
            final Path path = Files.createTempFile("armeria", "tmp");
            files.compute(bodyPart.name(), (s, paths) -> {
                if (paths == null) {
                    paths = new ArrayList<>();
                }
                paths.add(path);
                return paths;
            });
            eventLoop.execute(() -> {
                toPath(bodyPart, path, eventLoop).handleAsync((path1, throwable) -> {
                    inProgressCount--;
                    if (throwable != null) {
                        multipartSubscription.cancel();
                        future.completeExceptionally(throwable);
                        return null;
                    }
                    if (canComplete) {
                        doComplete();
                    } else {
                        multipartSubscription.request(1);
                    }
                    return null;
                });
            });
        } catch (IOException e) {
            multipartSubscription.cancel();
            future.completeExceptionally(e);
        }
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
        future.complete(new CustomAggregatedMultipart(queryParamsBuilder.build(), files));
    }

    static class CustomAggregatedMultipart {
        private final QueryParams queryParams;
        private final Map<String, List<Path>> files;

        CustomAggregatedMultipart(QueryParams queryParams, Map<String, List<Path>> files) {
            this.queryParams = queryParams;
            this.files = files;
        }

        public QueryParams getQueryParams() {
            return queryParams;
        }

        public Map<String, List<Path>> getFiles() {
            return files;
        }
    }
}
