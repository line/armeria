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

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;

import reactor.core.scheduler.NonBlocking;

/**
 * A {@link CompletableFuture} that warns the user if they call a method that blocks the event loop.
 */
public final class EventLoopCheckingCompletableFuture<T> extends CompletableFuture<T> {

    private static final Logger logger = LoggerFactory.getLogger(EventLoopCheckingCompletableFuture.class);

    private static final Set<Thread> REPORTED_THREADS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

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

    private static void maybeLogIfOnEventLoop() {
        if (!Flags.reportBlockedEventLoop()) {
            return;
        }
        final Thread thread = Thread.currentThread();
        if (thread instanceof NonBlocking && REPORTED_THREADS.add(thread)) {
            logger.warn("Calling a blocking method on CompletableFuture from an event loop thread. You " +
                        "should never do this as this will usually result in significantly reduced " +
                        "performance of the server, generally crippling its ability to handle high load, or " +
                        "even result in deadlock which cannot be recovered from. Use " +
                        "ServiceRequestContext.blockingExecutor to run this logic instead or switch to using " +
                        "asynchronous methods like thenApply. If you really believe it is fine to block the " +
                        "event loop like this, you can disable this log message by specifying the " +
                        "-Dcom.linecorp.armeria.reportBlockedEventLoop=false system property",
                        new IllegalStateException("Blocking event loop, don't do this."));
        }
    }
}
