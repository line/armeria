/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.internal.common.grpc;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Shares the exception-routing logic between the {@link CallExecutor} implementations.
 */
abstract class AbstractCallExecutor implements CallExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCallExecutor.class);

    private final RequestContext ctx;
    private final Consumer<? super Throwable> exceptionHandler;

    AbstractCallExecutor(RequestContext ctx, Consumer<? super Throwable> exceptionHandler) {
        this.ctx = ctx;
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Returns the {@link RequestContext} of the call this executor belongs to.
     */
    protected final RequestContext ctx() {
        return ctx;
    }

    /**
     * Runs the task with {@link #ctx()} pushed and routes any {@link Throwable} it throws to the exception
     * handler. A misbehaving handler is logged instead of propagating, so that a drain loop can always
     * continue with the tasks queued behind the failed one.
     */
    protected final void runTask(Runnable task) {
        try (SafeCloseable ignored = ctx.push()) {
            try {
                task.run();
            } catch (Throwable cause) {
                try {
                    exceptionHandler.accept(cause);
                } catch (Throwable handlerCause) {
                    logger.warn("Unexpected exception from the exception handler of {} " +
                                "while handling an exception from a task: {}", this, cause, handlerCause);
                }
            }
        }
    }
}
