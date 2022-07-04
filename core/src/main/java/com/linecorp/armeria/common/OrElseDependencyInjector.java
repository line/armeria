/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OrElseDependencyInjector implements DependencyInjector {

    private static final Logger logger = LoggerFactory.getLogger(OrElseDependencyInjector.class);

    private final DependencyInjector first;
    private final DependencyInjector second;

    OrElseDependencyInjector(DependencyInjector first, DependencyInjector second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public <T> T getInstance(Class<T> type) {
        final T instance = first.getInstance(type);
        if (instance != null) {
            return instance;
        }
        return second.getInstance(type);
    }

    @Override
    public void close() {
        close(first);
        close(second);
    }

    private static void close(DependencyInjector dependencyInjector) {
        try {
            dependencyInjector.close();
        } catch (Throwable t) {
            logger.warn("Unexpected exception while closing {}", dependencyInjector, t);
        }
    }
}
