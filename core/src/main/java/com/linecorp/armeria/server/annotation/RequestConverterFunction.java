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

import com.linecorp.armeria.common.AggregatedHttpMessage;

/**
 * Converts an {@link AggregatedHttpMessage} to an object. The class implementing this interface would
 * be specified as a value of a {@link RequestConverter} annotation.
 *
 * @see RequestConverter
 * @see RequestObject
 */
@FunctionalInterface
public interface RequestConverterFunction {

    /**
     * Returns whether this converter is able to convert the specified {@code request} to
     * {@code expectedResultType}.
     */
    default boolean accept(AggregatedHttpMessage request, Class<?> expectedResultType) {
        return true;
    }

    /**
     * Converts the specified {@code request} to an object of {@code expectedResultType}.
     */
    Object convert(AggregatedHttpMessage request, Class<?> expectedResultType) throws Exception;
}
