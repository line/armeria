/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

/**
 * Builds a {@link RetrofitMeterIdPrefixFunction}.
 *
 * @see RetrofitMeterIdPrefixFunction#builder(String)
 */
public final class RetrofitMeterIdPrefixFunctionBuilder {

    private final String name;
    @Nullable
    private String serviceTagName;
    @Nullable
    private String defaultServiceName;

    RetrofitMeterIdPrefixFunctionBuilder(String name) {
        this.name = name;
    }

    /**
     * Adds a tag that signifies the service name to the generated {@link MeterIdPrefix}es.
     *
     * @param serviceTagName the name of the tag to be added, e.g. {@code "serviceName"}
     * @param defaultServiceName the default value of the tag, e.g. {@code "myService"}
     */
    public RetrofitMeterIdPrefixFunctionBuilder withServiceTag(String serviceTagName,
                                                               String defaultServiceName) {
        this.serviceTagName = requireNonNull(serviceTagName, "serviceTagName");
        this.defaultServiceName = requireNonNull(defaultServiceName, "defaultServiceName");
        return this;
    }

    /**
     * Returns a newly created {@link RetrofitMeterIdPrefixFunction} with the properties specified so far.
     */
    public RetrofitMeterIdPrefixFunction build() {
        return new RetrofitMeterIdPrefixFunction(name, serviceTagName, defaultServiceName);
    }
}
