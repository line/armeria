/*
 * Copyright 2023 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

abstract class AbstractContextAwareExecutor<E extends Executor> implements Executor {
    enum LogRequestContextWarningOnce implements Supplier<RequestContext> {
        INSTANCE;

        @Override
        @Nullable
        public RequestContext get() {
            ClassLoaderHack.loadMe();
            return null;
        }

        /**
         * This won't be referenced until {@link #get()} is called. If there's only one classloader, the
         * initializer will only be called once.
         */
        private static final class ClassLoaderHack {
            static void loadMe() {}

            static {
                logger.warn(
                        "Attempted to propagate request context to an executor task, " +
                        "but no request context available. " +
                        "If this executor is used for non-request-related tasks then it's safe to ignore this",
                        new NoRequestContextException());
            }
        }

        private static final class NoRequestContextException extends RuntimeException {
            private static final long serialVersionUID = 2804189311774982052L;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(AbstractContextAwareExecutor.class);
    private final E executor;

    AbstractContextAwareExecutor(E executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    @Nullable
    abstract RequestContext contextOrNull();

    public final E withoutContext() {
        return executor;
    }

    final Runnable makeContextAware(Runnable task) {
        final RequestContext context = contextOrNull();
        return context == null ? task : context.makeContextAware(task);
    }

    @Override
    public final void execute(Runnable command) {
        executor.execute(makeContextAware(command));
    }
}
