/*
 * Copyright 2024 LINE Corporation
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceOptions;

/**
 * An annotation used to configure {@link HttpServiceOptions} of a {@link HttpService}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD })
public @interface HttpServiceOption {

    /**
     * Server-side timeout of a request in milliseconds.
     */
    long requestTimeoutMillis() default -1;

    /**
     * Server-side maximum length of a request.
     */
    long maxRequestLength() default -1;

    /**
     * The amount of time to wait before aborting an {@link HttpRequest} when its corresponding
     * {@link HttpResponse} is complete.
     */
    long requestAutoAbortDelayMillis() default -1;
}
