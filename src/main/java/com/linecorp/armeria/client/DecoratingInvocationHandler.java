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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * A {@link InvocationHandler} that decorates another {@link InvocationHandler}.
 *
 * @see ClientOption#INVOCATION_HANDLER_DECORATOR
 */
public abstract class DecoratingInvocationHandler implements InvocationHandler {

    private final RemoteInvoker delegate;

    protected DecoratingInvocationHandler(RemoteInvoker delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link InvocationHandler} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends InvocationHandler> T delegate() {
        return (T) delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return delegate().invoke(proxy, method, args);
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate() + ')';
    }
}
