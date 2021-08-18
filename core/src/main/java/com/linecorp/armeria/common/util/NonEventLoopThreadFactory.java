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

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * {@link ThreadFactory} that creates non event loop threads.
 */
final class NonEventLoopThreadFactory extends AbstractThreadFactory {
    NonEventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority,
                              @Nullable ThreadGroup threadGroup,
                              Function<? super Runnable, ? extends Runnable> taskFunction) {
        super(threadNamePrefix, daemon, priority, threadGroup, taskFunction);
    }

    @Override
    Thread newThread(@Nullable ThreadGroup threadGroup, Runnable r, String name) {
        return new FastThreadLocalThread(threadGroup, r, name);
    }
}
