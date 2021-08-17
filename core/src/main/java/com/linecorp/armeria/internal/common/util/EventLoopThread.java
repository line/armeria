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
package com.linecorp.armeria.internal.common.util;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import reactor.core.scheduler.NonBlocking;

/**
 * An event loop thread with support for {@link TemporaryThreadLocals}, Netty {@link FastThreadLocal} and
 * Project Reactor {@link NonBlocking}.
 */
public final class EventLoopThread extends FastThreadLocalThread implements NonBlocking {

    final TemporaryThreadLocals temporaryThreadLocals = new TemporaryThreadLocals();

    /**
     * Creates a new instance.
     */
    public EventLoopThread(@Nullable ThreadGroup threadGroup, Runnable r, String name) {
        super(threadGroup, r, name);
    }
}
