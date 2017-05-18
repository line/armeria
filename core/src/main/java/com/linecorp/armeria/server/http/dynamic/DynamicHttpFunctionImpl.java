/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.http.dynamic;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link DynamicHttpFunction} implementation backed by Java Reflection. Methods whose
 * return type is not {@link CompletionStage} or {@link HttpResponse} will be run in the
 * blocking task executor.
 *
 * @see DynamicHttpServiceBuilder#addMappings(Object)
 */
final class DynamicHttpFunctionImpl implements DynamicHttpFunction {

    private final Object object;
    private final Method method;
    private final List<ParameterEntry> parameterEntries;
    private final boolean isAsynchronous;

    DynamicHttpFunctionImpl(Object object, Method method) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        this.parameterEntries = Methods.parameterEntries(method);
        this.isAsynchronous =
                HttpResponse.class.isAssignableFrom(method.getReturnType()) ||
                CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Returns the set of parameter names which have a annotation of {@link PathParam}.
     */
    Set<String> pathParamNames() {
        return parameterEntries.stream()
                               .filter(ParameterEntry::isPathParam)
                               .map(ParameterEntry::getName)
                               .collect(toImmutableSet());
    }

    /**
     * Returns array of parameters for method invocation.
     */
    private Object[] parameters(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args) {
        Object[] parameters = new Object[parameterEntries.size()];
        for (int i = 0; i < parameterEntries.size(); ++i) {
            ParameterEntry entry = parameterEntries.get(i);
            if (entry.isPathParam()) {
                String value = args.get(entry.getName());
                assert value != null;
                parameters[i] = Deserializers.deserialize(value, entry.getType());
            } else if (entry.getType().isAssignableFrom(ServiceRequestContext.class)) {
                parameters[i] = ctx;
            } else if (entry.getType().isAssignableFrom(HttpRequest.class)) {
                parameters[i] = req;
            }
        }
        return parameters;
    }

    @Override
    public Object serve(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args) throws Exception {
        Object[] parameters = parameters(ctx, req, args);
        if (isAsynchronous) {
            return method.invoke(object, parameters);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return method.invoke(object, parameters);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }, ctx.blockingTaskExecutor());
    }
}
