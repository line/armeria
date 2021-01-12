/*
 * Copyright 2021 LINE Corporation
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

import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;

import com.linecorp.armeria.client.WebClientBuilder;

/**
 * A configurator to configure an {@link WebClientBuilder}.
 */
@FunctionalInterface
public interface ArmeriaClientConfigurator extends Ordered {
    /**
     * Configures the client using the specified {@link WebClientBuilder}.
     */
    void configure(WebClientBuilder builder);

    /**
     * Returns the evaluation order of this configurator. A user can specify the order with an {@link Order}
     * annotation when defining a bean with a {@link Bean} annotation.
     *
     * <p>Note that the default value of the {@link Order} annotation is {@link Ordered#LOWEST_PRECEDENCE}
     * which equals to {@link Integer#MAX_VALUE}, but it is overridden to {@code 0} by this default method.
     *
     * @see Ordered#LOWEST_PRECEDENCE
     * @see Ordered#HIGHEST_PRECEDENCE
     * @see AnnotationAwareOrderComparator
     */
    @Override
    default int getOrder() {
        return 0;
    }
}
