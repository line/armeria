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

package com.linecorp.armeria.internal.server.annotation;

import java.util.List;

import com.linecorp.armeria.server.AnnotatedServiceFacade;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.ServiceName;

/**
 * An {@link HttpService} which is defined by a {@link Path} or HTTP method annotations.
 * This class is designed to provide a common interface for {@link AnnotatedService}
 * internally. {@link AnnotatedServiceFacade} is intended for public access.
 * Please check out the documentation at
 * <a href="https://armeria.dev/docs/server-annotated-service">Annotated HTTP Service</a> to use this.
 */
public interface AnnotatedService extends AnnotatedServiceFacade {

    /**
     * Returns service name for this {@link AnnotatedService}.
     */
    String serviceName();

    /**
     * Returns a boolean value indicating whether the
     * serviceName is specified by the {@link ServiceName}.
     */
    boolean serviceNameSetByAnnotation();

    /**
     * Returns method name which is annotated in {@link AnnotatedService}.
     */
    String methodName();

    /**
     * Returns a unique ID to distinguish overloaded methods.
     * If the method is not overloaded, it should return 0.
     */
    int overloadId();

    /**
     * Returns resolvers to resolve value from given annotations
     * like {@link Param}, {@link Header}, and so on.
     */
    List<AnnotatedValueResolver> annotatedValueResolvers();
}
