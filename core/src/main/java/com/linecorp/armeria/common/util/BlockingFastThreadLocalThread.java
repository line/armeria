/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A special {@link Thread} that provides fast access to {@link FastThreadLocal} variables.
 */
class BlockingFastThreadLocalThread extends Thread {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(
            BlockingFastThreadLocalThread.class);

    // This will be set to true if we have a chance to wrap the Runnable.
    private final boolean cleanupFastThreadLocals;

    private InternalThreadLocalMap threadLocalMap;

    BlockingFastThreadLocalThread() {
        cleanupFastThreadLocals = false;
    }

    BlockingFastThreadLocalThread(Runnable target) {
        super(FastThreadLocalRunnable.wrap(target));
        cleanupFastThreadLocals = true;
    }

    BlockingFastThreadLocalThread(ThreadGroup group, Runnable target) {
        super(group, FastThreadLocalRunnable.wrap(target));
        cleanupFastThreadLocals = true;
    }

    BlockingFastThreadLocalThread(String name) {
        super(name);
        cleanupFastThreadLocals = false;
    }

    BlockingFastThreadLocalThread(ThreadGroup group, String name) {
        super(group, name);
        cleanupFastThreadLocals = false;
    }

    BlockingFastThreadLocalThread(Runnable target, String name) {
        super(FastThreadLocalRunnable.wrap(target), name);
        cleanupFastThreadLocals = true;
    }

    BlockingFastThreadLocalThread(ThreadGroup group, Runnable target, String name) {
        super(group, FastThreadLocalRunnable.wrap(target), name);
        cleanupFastThreadLocals = true;
    }

    BlockingFastThreadLocalThread(ThreadGroup group, Runnable target, String name, long stackSize) {
        super(group, FastThreadLocalRunnable.wrap(target), name, stackSize);
        cleanupFastThreadLocals = true;
    }

    /**
     * Returns the internal data structure that keeps the thread-local variables bound to this thread.
     * Note that this method is for internal use only, and thus is subject to change at any time.
     */
    public final InternalThreadLocalMap threadLocalMap() {
        if (this != Thread.currentThread() && logger.isWarnEnabled()) {
            logger.warn(new RuntimeException("It's not thread-safe to get 'threadLocalMap' " +
                                             "which doesn't belong to the caller thread"));
        }
        return threadLocalMap;
    }

    /**
     * Sets the internal data structure that keeps the thread-local variables bound to this thread.
     * Note that this method is for internal use only, and thus is subject to change at any time.
     */
    public final void setThreadLocalMap(InternalThreadLocalMap threadLocalMap) {
        if (this != Thread.currentThread() && logger.isWarnEnabled()) {
            logger.warn(new RuntimeException("It's not thread-safe to set 'threadLocalMap' " +
                                             "which doesn't belong to the caller thread"));
        }
        this.threadLocalMap = threadLocalMap;
    }

    /**
     * Returns {@code true} if {@link FastThreadLocal#removeAll()} will be called once
     * {@link #run()} completes.
     */
    @UnstableApi
    public boolean willCleanupFastThreadLocals() {
        return cleanupFastThreadLocals;
    }

    /**
     * Returns {@code true} if {@link FastThreadLocal#removeAll()} will be called once
     * {@link Thread#run()} completes.
     */
    @UnstableApi
    public static boolean willCleanupFastThreadLocals(Thread thread) {
        return thread instanceof BlockingFastThreadLocalThread &&
               ((BlockingFastThreadLocalThread) thread).willCleanupFastThreadLocals();
    }
}
