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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class HttpMessageAggregator {

    private static final SubscriptionOption[] POOLED_OBJECTS = { SubscriptionOption.WITH_POOLED_OBJECTS };
    private static final SubscriptionOption[] EMPTY_OPTIONS = {};

    static CompletableFuture<AggregatedHttpRequest> aggregateRequest(HttpRequest request,
                                                                     EventExecutor executor,
                                                                     @Nullable ByteBufAllocator alloc) {
        final SubscriptionOption[] options = alloc != null ? POOLED_OBJECTS : EMPTY_OPTIONS;

        return UnmodifiableFuture.wrap(request.collect(executor, options).thenApply(objects -> {
            final int size = objects.size();
            final RequestHeaders headers = request.headers();

            // Fast paths
            if (size == 0) {
                return AggregatedHttpRequest.of(headers);
            }

            if (size == 1) {
                final HttpObject first = objects.get(0);
                if (first instanceof HttpHeaders) {
                    return AggregatedHttpRequest.of(headers, HttpData.empty(), (HttpHeaders) first);
                } else {
                    return AggregatedHttpRequest.of(headers, (HttpData) first);
                }
            }

            if (size == 2) {
                final HttpObject first = objects.get(0);
                final HttpObject second = objects.get(1);
                if (first instanceof HttpHeaders) {
                    // Ignore data after trailers
                    PooledObjects.close(second);
                    return AggregatedHttpRequest.of(headers, HttpData.empty(), (HttpHeaders) first);
                }

                if (second instanceof HttpHeaders) {
                    return AggregatedHttpRequest.of(headers, (HttpData) first, (HttpHeaders) second);
                }

                final HttpData data = aggregateData((HttpData) first, (HttpData) second, alloc);
                return AggregatedHttpRequest.of(headers, data);
            }

            // Slow path
            int contentLength = 0;
            int dataEnd = -1;
            HttpHeaders trailers = HttpHeaders.of();
            for (int i = 0; i < size; i++) {
                final HttpObject httpObject = objects.get(i);
                if (dataEnd > -1) {
                    // Ignore 'HttpObject's written after a trailers.
                    PooledObjects.close(httpObject);
                } else {
                    if (httpObject instanceof HttpHeaders) {
                        trailers = (HttpHeaders) httpObject;
                        dataEnd = i;
                    } else {
                        contentLength += ((HttpData) httpObject).length();
                    }
                }
            }
            if (dataEnd == -1) {
                dataEnd = size;
            }

            if (contentLength == 0) {
                return AggregatedHttpRequest.of(headers, HttpData.empty(), trailers);
            }

            final HttpData content = aggregateData(objects, contentLength, 0, dataEnd, alloc);
            return AggregatedHttpRequest.of(headers, content, trailers);
        }));
    }

    static CompletableFuture<AggregatedHttpResponse> aggregateResponse(HttpResponse response,
                                                                       EventExecutor executor,
                                                                       @Nullable ByteBufAllocator alloc) {
        final SubscriptionOption[] options = alloc != null ? POOLED_OBJECTS : EMPTY_OPTIONS;

        return UnmodifiableFuture.wrap(response.collect(executor, options).thenApply(objects -> {
            final int size = objects.size();
            checkState(size >= 1, "An aggregated message does not have headers.");

            final ResponseHeaders headers = (ResponseHeaders) objects.get(0);
            boolean foundNonInformationalHeaders = !headers.status().isInformational();
            if (foundNonInformationalHeaders) {
                // Fast paths
                if (size == 1) {
                    return AggregatedHttpResponse.of(headers);
                }

                if (size == 2) {
                    final HttpObject second = objects.get(1);
                    if (second instanceof HttpHeaders) {
                        return AggregatedHttpResponse.of(headers, HttpData.empty(), (HttpHeaders) second);
                    } else {
                        return AggregatedHttpResponse.of(headers, (HttpData) second);
                    }
                }

                if (size == 3) {
                    final HttpObject second = objects.get(1);
                    final HttpObject third = objects.get(2);
                    if (second instanceof HttpHeaders) {
                        // Ignore data after trailers
                        PooledObjects.close(third);
                        return AggregatedHttpResponse.of(headers, HttpData.empty(), (HttpHeaders) second);
                    }

                    if (third instanceof HttpHeaders) {
                        return AggregatedHttpResponse.of(headers, (HttpData) second, (HttpHeaders) third);
                    }

                    final HttpData data = aggregateData((HttpData) second, (HttpData) third, alloc);
                    return AggregatedHttpResponse.of(headers, data);
                }
            }

            // Slow path
            List<ResponseHeaders> informationals = ImmutableList.of();
            ResponseHeaders responseHeaders = headers;
            int dataStart = 1;
            if (!foundNonInformationalHeaders) {
                informationals = new ArrayList<>(2);
                informationals.add(headers);
                for (int i = 1; i < size; i++) {
                    final HttpObject httpObject = objects.get(i);
                    if (httpObject instanceof ResponseHeaders) {
                        final ResponseHeaders headers0 = (ResponseHeaders) httpObject;
                        if (headers0.status().isInformational()) {
                            // A new informational headers
                            informationals.add(headers0);
                        } else {
                            foundNonInformationalHeaders = true;
                            responseHeaders = headers0;
                            dataStart = i + 1;
                            break;
                        }
                    } else if (httpObject instanceof HttpHeaders) {
                        // Append to the last informational headers
                        final HttpHeaders headers0 = (HttpHeaders) httpObject;
                        final int lastIdx = informationals.size() - 1;
                        informationals.set(lastIdx,
                                           informationals.get(lastIdx).withMutations(h -> h.add(headers0)));
                    }
                }
            }

            if (!foundNonInformationalHeaders) {
                for (int i = dataStart; i < size; i++) {
                    PooledObjects.close(objects.get(i));
                }
                throw new IllegalStateException(
                        "An aggregated message does not have a non-informational headers.");
            }

            int dataEnd = -1;
            int contentLength = 0;
            HttpHeaders trailers = HttpHeaders.of();
            for (int i = dataStart; i < size; i++) {
                final HttpObject httpObject = objects.get(i);
                if (dataEnd > 0) {
                    // Ignore 'HttpObject's written after a trailers.
                    PooledObjects.close(httpObject);
                } else {
                    if (httpObject instanceof HttpHeaders) {
                        trailers = (HttpHeaders) httpObject;
                        dataEnd = i;
                    } else {
                        contentLength += ((HttpData) httpObject).length();
                    }
                }
            }
            if (dataEnd == -1) {
                dataEnd = size;
            }

            if (contentLength == 0) {
                return AggregatedHttpResponse.of(informationals, responseHeaders, HttpData.empty(), trailers);
            }

            final HttpData content = aggregateData(objects, contentLength, dataStart, dataEnd, alloc);
            return AggregatedHttpResponse.of(informationals, responseHeaders, content, trailers);
        }));
    }

    private static HttpData aggregateData(List<HttpObject> objects, int contentLength, int start, int end,
                                          @Nullable ByteBufAllocator alloc) {
        if (alloc != null) {
            final ByteBuf merged = alloc.buffer(contentLength);
            for (int i = start; i < end; i++) {
                try (HttpData data = (HttpData) objects.get(i)) {
                    final ByteBuf buf = data.byteBuf();
                    if (data.isEmpty()) {
                        data.close();
                    } else {
                        merged.writeBytes(buf, buf.readerIndex(), data.length());
                    }
                }
            }
            return HttpData.wrap(merged);
        } else {
            final byte[] merged = new byte[contentLength];
            for (int i = start, offset = 0; i < end; i++) {
                final HttpData data = (HttpData) objects.get(i);
                final int dataLength = data.length();
                if (dataLength > 0) {
                    System.arraycopy(data.array(), 0, merged, offset, dataLength);
                    offset += dataLength;
                }
            }
            return HttpData.wrap(merged);
        }
    }

    private static HttpData aggregateData(HttpData data1, HttpData data2, @Nullable ByteBufAllocator alloc) {
        if (data2.isEmpty()) {
            return data1;
        }
        if (data1.isEmpty()) {
            return data2;
        }

        final int data1Length = data1.length();
        final int data2Length = data2.length();
        final int contentLength = data1Length + data2Length;
        if (alloc != null) {
            final ByteBuf merged = alloc.buffer(contentLength);
            final ByteBuf buf1 = data1.byteBuf();
            merged.writeBytes(buf1, buf1.readerIndex(), data1Length);
            final ByteBuf buf2 = data2.byteBuf();
            merged.writeBytes(buf2, buf2.readerIndex(), data2Length);
            return HttpData.wrap(merged);
        } else {
            final byte[] merged = new byte[contentLength];
            System.arraycopy(data1.array(), 0, merged, 0, data1Length);
            System.arraycopy(data2.array(), 0, merged, data1Length, data2Length);
            return HttpData.wrap(merged);
        }
    }

    private HttpMessageAggregator() {}
}
