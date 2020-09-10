/*
 * Copyright 2020 LINE Corporation
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * An {@link HttpResponse} which splits a stream of {@link HttpObject} into HTTP headers and payloads.
 * {@link #informationalHeaders()}, {@link #headers()} will be completed before publishing the first element of
 * {@link HttpData}. {@link #trailers()} might not complete until the entire {@link HttpData} has been consumed.
 */
public interface SplitHttpResponse {

    /**
     * Returns a {@link CompletableFuture} completed with a list of
     * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status#Information_responses">informational
     * </a> {@link ResponseHeaders}.
     */
    CompletableFuture<List<ResponseHeaders>> informationalHeaders();

    /**
     * Returns a {@link CompletableFuture} completed with a non-informational {@link ResponseHeaders}.
     */
    CompletableFuture<ResponseHeaders> headers();

    /**
     * Returns a {@link StreamMessage} publishes HTTP payloads as a stream of {@link HttpData}.
     */
    @CheckReturnValue
    StreamMessage<HttpData> body();

    /**
     * Returns a {@link CompletableFuture} completed with a {@linkplain HttpHeaders trailers}.
     * If an {@link HttpResponse} does not contain trailers, the returned {@link CompletableFuture} will be
     * completed with an {@linkplain HttpHeaders#of() empty headers}.
     */
    CompletableFuture<HttpHeaders> trailers();
}
