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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.metric.MeterIdFunction;

/**
 * Produces a {@link MeterIdFunction} from a service name.
 */
@FunctionalInterface
public interface MeterIdFunctionFactory {

    /**
     * The default {@link MeterIdFunctionFactory} instance.
     */
    MeterIdFunctionFactory DEFAULT = (type, serviceName) ->
            MeterIdFunction.ofDefault("armeria." + requireNonNull(type, "type"))
                           .withTags("service", requireNonNull(serviceName, "serviceName"));

    /**
     * Returns the {@link MeterIdFunction} for the specified service name.
     */
    MeterIdFunction get(String type, String serviceName);
}
