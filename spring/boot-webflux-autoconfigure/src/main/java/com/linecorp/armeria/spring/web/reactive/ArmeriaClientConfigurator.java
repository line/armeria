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
package com.linecorp.armeria.spring.web.reactive;

import com.linecorp.armeria.client.HttpClientBuilder;

/**
 * A customizer to customize an {@link HttpClientBuilder} for an {@link ArmeriaClientHttpConnector}.
 */
@FunctionalInterface
public interface ArmeriaClientConfigurator {
    /**
     * Configures the client using the specified {@link HttpClientBuilder}.
     */
    void customize(HttpClientBuilder builder);
}
