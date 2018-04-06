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
package com.linecorp.armeria.spring;

import com.linecorp.armeria.server.ServerBuilder;

/**
 * Interface used to configure a service on the default armeria server. Can be
 * used to register arbitrary services. When possible, it is usually preferable
 * to use convenience beans like {@link ThriftServiceRegistrationBean}.
 */
@FunctionalInterface
public interface ArmeriaServerConfigurator {
    /**
     * Configures the server using the specified {@link ServerBuilder}.
     */
    void configure(ServerBuilder serverBuilder);
}
