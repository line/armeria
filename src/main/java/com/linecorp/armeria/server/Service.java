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
import java.util.function.Function;

/**
 * Handles a request received by a {@link Server}. A {@link Service} is composed of:
 * <ul>
 *   <li>{@link ServiceCodec} - decodes the content of a request into an invocation and
 *                              encodes the result produced by {@link ServiceInvocationHandler}</li>
 *   <li>{@link ServiceInvocationHandler} - handles the invocation decoded by a {@link ServiceCodec} and
 *                                          produces the result of the invocation</li>
 * </ul>
 */
public interface Service {

    /**
     * Creates a new {@link Service} with the specified {@link ServiceCodec} and
     * {@link ServiceInvocationHandler}.
     */
    static Service of(ServiceCodec codec, ServiceInvocationHandler handler) {
        return new SimpleService(codec, handler);
    }

    /**
     * Creates a new function that decorates a {@link Service} with the specified {@code codecDecorator}
     * and {@code handlerDecorator}.
     * <p>
     * This factory method is useful when you want to write a reusable factory method that returns
     * a decorator function and the returned decorator function is expected to be consumed by
     * {@link #decorate(Function)}. For example, this may be a factory which combines multiple decorators,
     * any of which could be decorating a codec and/or a handler.
     * </p><p>
     * Consider using {@link #decorateCodec(Function)} or {@link #decorateHandler(Function)}
     * instead for simplicity unless you are writing a factory method of a decorator function.
     * </p><p>
     * If you need a function that decorates only a codec or a handler, use {@link Function#identity()} for
     * the non-decorated property. e.g:
     * </p><pre>{@code
     * newDecorator(Function.identity(), (handler) -> { ... });
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    static <T extends ServiceCodec, U extends ServiceCodec,
            V extends ServiceInvocationHandler, W extends ServiceInvocationHandler>
    Function<Service, Service> newDecorator(Function<T, U> codecDecorator, Function<V, W> handlerDecorator) {
        return service -> new DecoratingService(service, codecDecorator, handlerDecorator);
    }

    /**
     * Invoked when this {@link Service} has been added to the specified {@link Server}.
     */
    default void serviceAdded(Server server) throws Exception {}

    /**
     * Returns the {@link ServiceCodec} of this {@link Service}.
     */
    ServiceCodec codec();

    /**
     * Returns the {@link ServiceInvocationHandler} of this {@link Service}.
     */
    ServiceInvocationHandler handler();

    /**
     * Undecorates this {@link Service} to find the {@link Service} which is an instance of the specified
     * {@code serviceType}. Use this method instead of an explicit downcast since most {@link Service}s are
     * decorated via {@link #decorate(Function)}, {@link #decorateCodec(Function)} or
     * {@link #decorateHandler(Function)} and thus cannot be downcast. For example:
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
    default <T extends Service> Optional<T> as(Class<T> serviceType) {
        requireNonNull(serviceType, "serviceType");
        if (serviceType.isInstance(this)) {
            return Optional.of(serviceType.cast(this));
        }

        return Optional.empty();
    }

    /**
     * Creates a new {@link Service} that decorates this {@link Service} with the specified {@code decorator}.
     */
    default <T extends Service, U extends Service> Service decorate(Function<T, U> decorator) {
        @SuppressWarnings("unchecked")
        final Service newService = decorator.apply((T) this);

        if (newService != null) {
            return newService;
        } else {
            return this;
        }
    }

    /**
     * Creates a new {@link Service} that decorates the {@link ServiceCodec} of this {@link Service} with the
     * specified {@code codecDecorator}.
     */
    default <T extends ServiceCodec, U extends ServiceCodec>
    Service decorateCodec(Function<T, U> codecDecorator) {
        return new DecoratingService(this, codecDecorator, Function.identity());
    }

    /**
     * Creates a new {@link Service} that decorates the {@link ServiceInvocationHandler} of this
     * {@link Service} with the specified {@code handlerDecorator}.
     */
    default <T extends ServiceInvocationHandler, U extends ServiceInvocationHandler>
    Service decorateHandler(Function<T, U> handlerDecorator) {
        return new DecoratingService(this, Function.identity(), handlerDecorator);
    }
}
