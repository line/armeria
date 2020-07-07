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
package com.linecorp.armeria.spring;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

enum DefaultMeterIdPrefixFunctionFactory implements MeterIdPrefixFunctionFactory {
    INSTANCE {
        @Override
        public MeterIdPrefixFunction get(String type, String serviceName) {
            return MeterIdPrefixFunction.ofDefault("armeria." + requireNonNull(type, "type"))
                                        .withTags("service", requireNonNull(serviceName, "serviceName"));
        }
    }
}
