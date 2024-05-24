/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import java.lang.reflect.Method;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * An {@link HttpService} which is defined by a {@link Path} or HTTP method annotations.
 * This class is designed to provide a common interface and expose as public for Annotated HTTP Service.
 * Please check out the documentation at
 * <a href="https://armeria.dev/docs/server-annotated-service">Annotated HTTP Service</a> to use this.
 */
@Nullable
public interface AnnotatedService extends HttpService {

    /**
     * Returns the name of this annotated service specified with {@link ServiceName}.
     */
    @Nullable
    String name();

    /**
     * Returns the annotated service object specified with {@link ServerBuilder#annotatedService(Object)}.
     */
    Object serviceObject();

    /**
     * Returns the {@link Class} of the annotated service object specified
     * with {@link ServerBuilder#annotatedService(Object)}.
     */
    Class<?> serviceClass();

    /**
     * Returns the target {@link Method} invoked when a request is received.
     */
    Method method();

    /**
     *  Returns the name of the target method invoked when a request is received.
     */
    default String methodName() {
        return method().getName();
    }

    /**
     * Returns the {@link Route} for this {@link AnnotatedService}.
     */
    Route route();

    /**
     * Returns the default {@link HttpStatus} specified with {@link StatusCode}.
     * If {@link StatusCode} is not given, {@link HttpStatus#OK} is returned by default.
     * If the method returns a void type such as {@link Void} or Kotlin Unit, {@link HttpStatus#NO_CONTENT} is
     * returned.
     */
    HttpStatus defaultStatus();
}
