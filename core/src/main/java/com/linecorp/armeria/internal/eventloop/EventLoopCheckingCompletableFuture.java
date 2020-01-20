/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.eventloop;

import static com.linecorp.armeria.internal.eventloop.EventLoopCheckingUtil.maybeLogIfOnEventLoop;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link CompletableFuture} that warns the user if they call a method that blocks the event loop.
 */
public class EventLoopCheckingCompletableFuture<T> extends CompletableFuture<T> {

    @Override
    public T get() throws InterruptedException, ExecutionException {
        maybeLogIfOnEventLoop();
        return super.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        maybeLogIfOnEventLoop();
        return super.get(timeout, unit);
    }

    @Override
    public T join() {
        maybeLogIfOnEventLoop();
        return super.join();
    }
}
