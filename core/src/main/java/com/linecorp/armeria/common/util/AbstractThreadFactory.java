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

package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A skeletal {@link ThreadFactory} implementation.
 */
abstract class AbstractThreadFactory implements ThreadFactory {
    // Note that we did not extend DefaultThreadFactory directly to hide it from the class hierarchy.
    private final ThreadFactoryImpl delegate;
    private final Function<? super Runnable, ? extends Runnable> taskFunction;

    AbstractThreadFactory(String threadNamePrefix, boolean daemon, int priority,
                          @Nullable ThreadGroup threadGroup,
                          Function<? super Runnable, ? extends Runnable> taskFunction) {
        delegate = new ThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix"),
                                         daemon, priority, threadGroup);
        this.taskFunction = requireNonNull(taskFunction, "taskFunction");
    }

    @Override
    public final Thread newThread(Runnable r) {
        final Runnable newRunnable = taskFunction.apply(r);
        checkState(newRunnable != null, "taskFunction.apply() returned null.");
        return delegate.newThread(newRunnable);
    }

    abstract Thread newThread(@Nullable ThreadGroup threadGroup, Runnable r, String name);

    private final class ThreadFactoryImpl extends DefaultThreadFactory {
        ThreadFactoryImpl(String threadNamePrefix, boolean daemon, int priority,
                          @Nullable ThreadGroup threadGroup) {
            super(threadNamePrefix, daemon, priority, threadGroup);
        }

        @Override
        protected Thread newThread(Runnable r, String name) {
            return AbstractThreadFactory.this.newThread(threadGroup, r, name);
        }
    }
}
