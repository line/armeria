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

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link DynamicHttpFunction} implementation backed by Java Reflection.
 *
 * @see DynamicHttpServiceBuilder#addMappings(Object)
 */
final class DynamicHttpFunctionImpl implements DynamicHttpFunction {

    private final Object object;
    private final Method method;
    private final ParameterEntry[] parameterEntries;

    DynamicHttpFunctionImpl(Object object, Method method) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        this.parameterEntries = Methods.parameterEntries(method);
    }

    /**
     * Returns the set of parameter names, which should be provided to invoke this function.
     */
    Set<String> parameterNames() {
        return Arrays.stream(parameterEntries).map(ParameterEntry::getName).collect(Collectors.toSet());
    }

    /**
     * Returns array of parameters for method invocation.
     */
    private Object[] parameters(Map<String, String> args) {
        Object[] parameters = new Object[parameterEntries.length];
        for (int i = 0; i < parameterEntries.length; ++i) {
            ParameterEntry entry = parameterEntries[i];
            String variable = entry.getName();
            String value = args.get(variable);
            assert value != null;
            Class<?> type = entry.getType();
            parameters[i] = Deserializers.deserialize(value, type);
        }
        return parameters;
    }

    @Override
    public Object serve(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args) throws Exception {
        Object[] parameters = parameters(args);
        return method.invoke(object, parameters);
    }
}
