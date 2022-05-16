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

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

/**
 * An {@link HttpResponse} which splits a stream of {@link HttpObject}s into HTTP headers and payloads.
 * {@link #headers()} will be completed before publishing the first {@link HttpData}.
 *
 * <p>Note that
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status#Information_responses">informational
 * headers</a> are not collected by this {@link HttpResponse}. If you want to get informational headers,
 * use {@link HttpResponse#subscribe(Subscriber)} instead.
 */
public interface SplitHttpResponse extends SplitHttpMessage {

    /**
     * Returns a {@link CompletableFuture} completed with a non-informational {@link ResponseHeaders}.
     */
    CompletableFuture<ResponseHeaders> headers();
}
