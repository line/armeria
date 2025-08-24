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
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Handle used by {@link AbstractRetryingClient} to drive the retrying process of a request.
 * In particular, it provides methods to
 *
 * <ul>
 *     <li>initialize the retrying process ({@link #init()}),</li>
 *     <li>execute a request attempt ({@link #executeAttempt(Backoff, Client)}),</li>
 *     <li>decide to abort the attempt and schedule a new one ({@link #abortAttempt()} and
 *     {@link #scheduleNextRetry(long, Runnable, Consumer)}) or to</li>
 *     <li>accept the response attempt as the final response of the request ({@link #commit()}).</li>
 * </ul>
 *
 * <p>
 *      Notes:
 *      <ul>
 *          <li>
 *              Calls to methods of this interface must be in a certain order. The method call order
 *              can be "seen" in {@link AbstractRetryingClient#execute(ClientRequestContext, Request)}. <br>
 *              Note that {@link #abort(Throwable)} and {@link #res()}
 *              can be called at any point, even before a call to {@link #init()}.
 *          </li>
 *          <li>Implementors of this interface must be thread-safe.</li>
 *      </ul>
 * </p>
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 * @see HttpRetryingContext
 * @see RpcRetryingContext
 */
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
