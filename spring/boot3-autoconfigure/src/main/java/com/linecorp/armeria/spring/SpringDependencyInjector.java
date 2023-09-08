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
package com.linecorp.armeria.spring;

import static java.util.Objects.requireNonNull;

import org.springframework.beans.factory.BeanFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Injects dependencies in annotations using the {@code BeanFactory}.
 */
@UnstableApi
public final class SpringDependencyInjector implements DependencyInjector {

    /**
     * Creates a new {@link SpringDependencyInjector} that injects dependencies in annotated services using
     * the specified {@link BeanFactory}.
     */
    public static SpringDependencyInjector of(BeanFactory beanFactory) {
        requireNonNull(beanFactory, "beanFactory");
        return new SpringDependencyInjector(beanFactory);
    }

    private final BeanFactory beanFactory;

    private SpringDependencyInjector(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public <T> T getInstance(Class<T> type) {
        return beanFactory.getBean(type);
    }

    @Override
    public void close() {
        // The lifecycle of the beanFactory is managed by Spring.
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("beanFactory", beanFactory)
                          .toString();
    }
}
