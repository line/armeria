/*
 * Copyright 2020 LINE Corporation
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

/**
 * Annotation for service name that is often used as a meter tag or distributed trace's span name.
 * For example:<pre>{@code
 * > public class MyService {
 * >     @Get("/")
 * >     public String get(ServiceRequestContext ctx) {
 * >         assert ctx.log().partial().serviceName() == MyService.class.getName();
 * >     }
 * > }
 *
 * > @ServiceName("my-service")
 * > public class MyService {
 * >     @Get("/")
 * >     public String get(ServiceRequestContext ctx) {
 * >         assert ctx.log().partial().serviceName() == "my-service";
 * >     }
 *
 * >     @ServiceName("my-post-service")
 * >     @Post("/")
 * >     public String post(ServiceRequestContext ctx) {
 * >         assert ctx.log().partial().serviceName() == "my-post-service";
 * >     }
 * > }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface ServiceName {

    /**
     * A service name for the annotated service.
     */
    String value();
}
