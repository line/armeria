/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.server.ServerBuilder;

/**
 * Annotation that can be used on a class as a path prefix for all the
 * methods that handle http request. For example
 * <pre>{@code
 * > @PathPrefix("/b")
 * > public class MyService {
 * >     @Get("/c")
 * >     public HttpResponse foo() { ... }
 * > }
 * }</pre>
 * And then can be registered to {@link ServerBuilder} like this
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.annotatedService("/a", new MyService());
 * }</pre>
 *
 * <p>In this case {@code foo()} method handles a request that matches path {@code /a/b/c}</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PathPrefix {

    /**
     * A path for the annotated service class.
     */
    String value();
}
