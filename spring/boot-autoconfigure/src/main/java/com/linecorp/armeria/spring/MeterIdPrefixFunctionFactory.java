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

import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

/**
 * Produces a {@link MeterIdPrefixFunction} from a service name.
 */
@FunctionalInterface
public interface MeterIdPrefixFunctionFactory {

    /**
     * The default {@link MeterIdPrefixFunctionFactory} instance.
     *
     * @deprecated Use {@link #ofDefault()}.
     */
    @Deprecated
    MeterIdPrefixFunctionFactory DEFAULT = DefaultMeterIdPrefixFunctionFactory.INSTANCE;

    /**
     * Returns the default {@link MeterIdPrefixFunctionFactory} instance.
     */
    static MeterIdPrefixFunctionFactory ofDefault() {
        return DefaultMeterIdPrefixFunctionFactory.INSTANCE;
    }

    /**
     * Returns the {@link MeterIdPrefixFunction} for the specified service name.
     */
    MeterIdPrefixFunction get(String type, String serviceName);
}
