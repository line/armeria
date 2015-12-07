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
 * A {@link Service} that decorates another {@link Service}. Do not use this class unless you want to define
 * a new dedicated {@link Service} type by extending this class; prefer:
 * <ul>
 *   <li>{@link Service#decorate(Function)}</li>
 *   <li>{@link Service#decorateHandler(Function)}</li>
 *   <li>{@link Service#decorateCodec(Function)}</li>
 *   <li>{@link Service#newDecorator(Function, Function)}</li>
 * </ul>
 *
 * @see DecoratingServiceCodec
 * @see DecoratingServiceInvocationHandler
 */
public class DecoratingService implements Service {

    private final Service service;
    private final ServiceCodec codec;
    private final ServiceInvocationHandler handler;

    /**
     * Creates a new instance that decorates the specified {@link Service} and its {@link ServiceCodec} and
     * {@link ServiceInvocationHandler} using the specified {@code codecDecorator} and {@code handlerDecorator}.
     */
    protected <T extends ServiceCodec, U extends ServiceCodec,
            V extends ServiceInvocationHandler, W extends ServiceInvocationHandler>
    DecoratingService(Service service, Function<T, U> codecDecorator, Function<V, W> handlerDecorator) {

        this.service = requireNonNull(service, "service");
        codec = decorateCodec(service, codecDecorator);
        handler = decorateHandler(service, handlerDecorator);
    }

    private static <T extends ServiceCodec, U extends ServiceCodec>
    ServiceCodec decorateCodec(Service service, Function<T, U> codecDecorator) {

        requireNonNull(codecDecorator, "codecDecorator");

        @SuppressWarnings("unchecked")
        final T codec = (T) service.codec();
        final U decoratedCodec = codecDecorator.apply(codec);

        return decoratedCodec != null ? decoratedCodec : codec;
    }

    private static <T extends ServiceInvocationHandler, U extends ServiceInvocationHandler>
    ServiceInvocationHandler decorateHandler(Service service, Function<T, U> handlerDecorator) {

        requireNonNull(handlerDecorator, "handlerDecorator");

        @SuppressWarnings("unchecked")
        final T handler = (T) service.handler();
        final U decoratedHandler = handlerDecorator.apply(handler);

        return decoratedHandler != null ? decoratedHandler : handler;
    }

    /**
     * Returns the {@link Service} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends Service> T delegate() {
        return (T) service;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        ServiceCallbackInvoker.invokeServiceAdded(cfg, delegate());
    }

    @Override
    public ServiceCodec codec() {
        return codec;
    }

    @Override
    public ServiceInvocationHandler handler() {
        return handler;
    }

    @Override
    public final <T extends Service> Optional<T> as(Class<T> serviceType) {
        final Optional<T> result = Service.super.as(serviceType);
        return result.isPresent() ? result : delegate().as(serviceType);
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate() + ')';
    }
}
