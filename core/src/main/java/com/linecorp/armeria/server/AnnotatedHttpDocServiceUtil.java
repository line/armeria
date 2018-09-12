/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.server.annotation.Cookies;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.docs.DocService;

import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.models.parameters.Parameter;

/**
 * A utility class to provide annotation processing from {@link AnnotatedHttpService} to build
 * {@link DocService}.
 */
final class AnnotatedHttpDocServiceUtil {

    @VisibleForTesting
    static final String QUERY_PARAM = "query";

    @VisibleForTesting
    static final String HEADER_PARAM = "header";

    @VisibleForTesting
    static final String COOKIE_PARAM = "cookie";

    @VisibleForTesting
    static final String PATH_PARAM = "path";

    /**
     * Returns {@code true} if the class or the method of the specified {@link AnnotatedHttpService} is
     * annotated with {@link Hidden}.
     */
    static boolean isHidden(AnnotatedHttpService service) {
        requireNonNull(service, "service");
        final Class<?> clazz = service.object().getClass();
        final Method method = service.method();

        final Hidden hidden = clazz.getAnnotation(Hidden.class);
        if (hidden != null) {
            return true;
        }

        final io.swagger.v3.oas.annotations.Operation operation = ReflectionUtils
                .getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        if (operation != null && operation.hidden()) {
            return true;
        }

        return method.getAnnotation(Hidden.class) != null;
    }

    @Nullable
    static String getNormalizedTriePath(HttpHeaderPathMapping pathMapping) {
        requireNonNull(pathMapping, "pathMapping");
        final Optional<String> triePath = pathMapping.triePath();

        if (!triePath.isPresent()) {
            return null;
        }

        final String path = triePath.get();
        int beginIndex = 0;

        final StringBuilder sb = new StringBuilder();
        for (String paramName : pathMapping.paramNames()) {
            final int colonIndex = path.substring(beginIndex).indexOf(':') + beginIndex;
            sb.append(path, beginIndex, colonIndex);
            sb.append('{');
            sb.append(paramName);
            sb.append('}');
            beginIndex = colonIndex + 1;
        }
        if (beginIndex < path.length()) {
            sb.append(path, beginIndex, path.length());
        }
        return sb.toString();
    }

    @Nullable
    static Parameter extractParameter(AnnotatedValueResolver resolver) {
        requireNonNull(resolver, "resolver");
        final Parameter parameter;

        if (resolver.isPathVariable()) {
            parameter = new Parameter();
            parameter.setIn(PATH_PARAM);
            parameter.setName(resolver.httpElementName());
        } else if (resolver.isAnnotationType(Param.class)) { // @Param whose path variable is false is a query.
            parameter = new Parameter();
            parameter.setIn(QUERY_PARAM);
            if (!resolver.shouldExist()) {
                // Allow sending a parameter with an empty value and this is valid only for query parameters.
                parameter.allowEmptyValue(true);
            }
            parameter.setName(resolver.httpElementName());
        } else if (resolver.isAnnotationType(Header.class)) {
            parameter = new Parameter();
            parameter.setIn(HEADER_PARAM);
            parameter.setName(resolver.httpElementName());
        } else if (resolver.isAnnotationType(RequestObject.class)) {
            parameter = new Parameter();
            parameter.setIn(QUERY_PARAM);
            final String elementName = resolver.httpElementName();
            parameter.setName(elementName != null ? elementName : resolver.elementType().getName());
        } else if (resolver.elementType() == Cookies.class) {
            parameter = new Parameter();
            parameter.setIn(COOKIE_PARAM);
            final String elementName = resolver.httpElementName();
            parameter.setName(elementName != null ? elementName : "cookie"); // TODO Provide a proper name
        } else {
            // The parameter is one of following:
            // - ServiceRequestContext
            // - HttpRequest
            // - AggregatedHttpMessage
            // - HttpParameters
            parameter = null;
        }

        if (parameter != null) {
            parameter.required(resolver.shouldExist());
            parameter.setDescription(resolver.description());
        }

        return parameter;
    }

    private AnnotatedHttpDocServiceUtil() {}
}
