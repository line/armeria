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
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Handles a {@link Request} received by a {@link Server}.
 *
 * @param <I> the type of incoming {@link Request}
 * @param <O> the type of outgoing {@link Response}
 */
@FunctionalInterface
public interface Service<I extends Request, O extends Response> {

    /**
     * Creates a new {@link Service} with the specified {@link BiFunction}.
     */
    static <I extends Request, O extends Response> Service<I, O> of(
            BiFunction<? super ServiceRequestContext, ? super I, ? extends O> function) {
        return new BiFunctionService<>(function);
    }

    /**
     * Invoked when this {@link Service} has been added to a {@link Server} with the specified configuration.
     * Please note that this method can be invoked more than once if ths {@link Service} has been added more
     * than once.
     */
    default void serviceAdded(ServiceConfig cfg) throws Exception {}

    /**
     * Serves an incoming {@link Request}.
     *
     * @param ctx the context of the received {@link Request}
     * @param req the received {@link Request}
     *
     * @return the {@link Response}
     */
    O serve(ServiceRequestContext ctx, I req) throws Exception;

    /**
     * Undecorates this {@link Service} to find the {@link Service} which is an instance of the specified
     * {@code serviceType}. Use this method instead of an explicit downcast since most {@link Service}s are
     * decorated via {@link #decorate(Function)} and thus cannot be downcast. For example:
     * <pre>{@code
     * Service s = new MyService().decorate(LoggingService::new).decorate(AuthService::new);
     * MyService s1 = s.as(MyService.class);
     * LoggingService s2 = s.as(LoggingService.class);
     * AuthService s3 = s.as(AuthService.class);
     * }</pre>
     *
     * @param serviceType the type of the desired {@link Service}
     * @return the {@link Service} which is an instance of {@code serviceType} if this {@link Service}
     *         decorated such a {@link Service}. {@link Optional#empty()} otherwise.
     */
    default <T extends Service<?, ?>> Optional<T> as(Class<T> serviceType) {
        requireNonNull(serviceType, "serviceType");
        return serviceType.isInstance(this) ? Optional.of(serviceType.cast(this))
                                            : Optional.empty();
    }

    /**
     * Creates a new {@link Service} that decorates this {@link Service} with the specified {@code decorator}.
     */
    default <T extends Service<? super I, ? extends O>,
             R extends Service<R_I, R_O>, R_I extends Request, R_O extends Response>
    R decorate(Function<T, R> decorator) {
        @SuppressWarnings("unchecked")
        final R newService = decorator.apply((T) this);

        if (newService == null) {
            throw new NullPointerException("decorator.apply() returned null: " + decorator);
        }

        return newService;
    }
}
