/*
 * Copyright 2017 LINE Corporation
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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
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
    private final boolean aggregationRequired;

    DynamicHttpFunctionImpl(Object object, Method method) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        parameterEntries = Methods.parameterEntries(method);
        isAsynchronous = HttpResponse.class.isAssignableFrom(method.getReturnType()) ||
                         CompletionStage.class.isAssignableFrom(method.getReturnType());
        aggregationRequired = parameterEntries.stream().anyMatch(
                entry -> entry.getType().isAssignableFrom(AggregatedHttpMessage.class));
    }

    /**
     * Returns the set of parameter names which have an annotation of {@link PathParam}.
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
    private Object[] parameters(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args,
                                @Nullable AggregatedHttpMessage message) {
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
            } else if (entry.getType().isAssignableFrom(AggregatedHttpMessage.class)) {
                parameters[i] = message;
            }
        }
        return parameters;
    }

    @Override
    public Object serve(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args) throws Exception {
        if (aggregationRequired) {
            if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                return req.aggregate()
                          .thenCompose(msg -> (CompletionStage<?>) invokeMethod(ctx, req, args, msg));
            } else if (isAsynchronous) {
                return req.aggregate()
                          .thenApply(msg -> invokeMethod(ctx, req, args, msg));
            } else {
                return req.aggregate()
                          .thenApplyAsync(msg -> invokeMethod(ctx, req, args, msg),
                                          ctx.blockingTaskExecutor());
            }
        }

        if (isAsynchronous) {
            return invokeMethod(ctx, req, args, null);
        } else {
            return CompletableFuture.supplyAsync(
                    () -> invokeMethod(ctx, req, args, null), ctx.blockingTaskExecutor());
        }
    }

    private Object invokeMethod(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args,
                                @Nullable AggregatedHttpMessage message) {
        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            Object[] parameters = parameters(ctx, req, args, message);
            return method.invoke(object, parameters);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }
}
