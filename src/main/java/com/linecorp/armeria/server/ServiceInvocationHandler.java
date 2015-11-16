/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.linecorp.armeria.common.ServiceInvocationContext;

import io.netty.util.concurrent.Promise;

/**
 * Handles the invocation request decoded by a {@link ServiceCodec}.
 *
 * @see Service
 */
@FunctionalInterface
public interface ServiceInvocationHandler {

    /**
     * Invoked when the {@link Service} of this {@link ServiceInvocationHandler} has been added to the
     * specified {@link Server}.
     */
    default void handlerAdded(Server server) throws Exception {}

    /**
     * Handles the invocation request and finishes the specified {@code promise} with its result.
     *
     * @param ctx the {@link ServiceInvocationContext} that provides the information about the invocation
     * @param blockingTaskExecutor an {@link Executor} to use when this handler has to perform a task that
     *                             would block the current thread
     * @param promise the {@link Promise} of the invocation, which is expected to be marked as done once
     *                the result of the invocation is produced.
     */
    void invoke(ServiceInvocationContext ctx, Executor blockingTaskExecutor,
                Promise<Object> promise) throws Exception;

    /**
     * Undecorates this {@link ServiceInvocationHandler} to find the {@link ServiceInvocationHandler} which is
     * an instance of the specified {@code handlerType}. Use this method instead of an explicit downcast since
     * most {@link ServiceInvocationHandler}s are decorated via {@link Service#decorate(Function)} or
     * {@link Service#decorateHandler(Function)} and thus cannot be downcast. For example:
     * <pre>{@code
     * Service s = new MyService().decorate(LoggingService::new).decorate(AuthService::new);
     * MyServiceHandler c1 = s.handler().as(MyServiceHandler.class);
     * LoggingServiceHandler c2 = s.handler().as(LoggingServiceHandler.class);
     * AuthServiceHandler c3 = s.handler().as(AuthServiceHandler.class);
     * }</pre>
     *
     * @param handlerType the type of the desired {@link ServiceInvocationHandler}
     * @return the {@link ServiceInvocationHandler} which is an instance of {@code handlerType} if this
     *         {@link ServiceInvocationHandler} decorated such a {@link ServiceInvocationHandler}.
     *         {@link Optional#empty()} otherwise.
     */
    default <T extends ServiceInvocationHandler> Optional<T> as(Class<T> handlerType) {
        requireNonNull(handlerType, "handlerType");
        if (handlerType.isInstance(this)) {
            return Optional.of(handlerType.cast(this));
        }

        return Optional.empty();
    }
}
