/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

interface RetryingContext<I extends Request, O extends Response> {
    CompletableFuture<Boolean> init();

    CompletableFuture<@Nullable RetryDecision> executeAttempt(@Nullable Backoff backoff, Client<I, O> delegate);

    long nextRetryTimeNanos(Backoff backoff);

    void scheduleNextRetry(long retryTimeNanos, Runnable retryTask,
                           Consumer<? super Throwable> exceptionHandler);

    void commit();

    void abortAttempt();

    void abort(Throwable cause);

    O res();
}
