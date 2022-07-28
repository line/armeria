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

import java.util.concurrent.CompletableFuture;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.stream.ByteStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * An {@link HttpMessage} which splits a stream of {@link HttpObject}s into payloads and HTTP trailers.
 * {@link #trailers()} might not complete until the entire payload is consumed completely.
 */
public interface SplitHttpMessage {

    /**
     * Returns a {@link StreamMessage} publishes HTTP payloads as a stream of {@link HttpData}.
     */
    @CheckReturnValue
    ByteStreamMessage body();

    /**
     * Returns a {@link CompletableFuture} completed with a {@linkplain HttpHeaders trailers}.
     * If an {@link HttpRequest} or {@link HttpResponse} does not contain trailers,
     * returned {@link CompletableFuture} will be completed with an {@linkplain HttpHeaders#of() empty headers}.
     */
    CompletableFuture<HttpHeaders> trailers();
}
