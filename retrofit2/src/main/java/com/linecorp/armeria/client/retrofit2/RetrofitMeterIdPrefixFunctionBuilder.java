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
    @Nullable
    private String serviceName;
    @Nullable
    private Class<?> serviceClass;

    RetrofitMeterIdPrefixFunctionBuilder(String name) {
        this.name = name;
    }

    RetrofitMeterIdPrefixFunctionBuilder(String name, Class<?> serviceClass) {
        this.name = name;
        this.serviceClass = serviceClass;
    }

    /**
     * Adds a tag that signifies the service name to the generated {@link MeterIdPrefix}es.
     *
     * @param serviceTagName the name of the tag to be added, e.g. {@code "service.name"}
     * @param defaultServiceName the default value of the tag, e.g. {@code "myService"}
     * @deprecated Please use {@link #serviceTag(String)} and {@link #serviceName(String)} instead.
     */
    @Deprecated
    public RetrofitMeterIdPrefixFunctionBuilder withServiceTag(String serviceTagName,
                                                               String defaultServiceName) {
        this.serviceTagName = requireNonNull(serviceTagName, "serviceTagName");
        this.defaultServiceName = requireNonNull(defaultServiceName, "defaultServiceName");
        return this;
    }

    /**
     * Renames a tag in generated metrics that indicate service name. Default name for the tag {@code service}.
     * Unless service name set with {@link #serviceName} in place of service name
     * would be used name of the retrofit client service class. In case retrofit client service class
     * cannot be defined {@code UNKNOWN} would be used.
     *
     * @param serviceTagName the name of the tag to be added, e.g.: {@code "serviceName"}
     */
    public RetrofitMeterIdPrefixFunctionBuilder serviceTag(String serviceTagName) {
        this.serviceTagName = requireNonNull(serviceTagName, "serviceTagName");
        return this;
    }

    /**
     * Define service name that should be used for metric with tag defined in {@link #serviceTag(String)}
     * instead of retrofit client service class.
     *
     * @param serviceName service name to be reported in metrics
     */
    public RetrofitMeterIdPrefixFunctionBuilder serviceName(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        return this;
    }

    /**
     * Adds retrofit client service class that would be used to provide additional tags for metrics
     * based on retrofit annotations. See {@link RetrofitClassAwareMeterIdPrefixFunction}.
     *
     * @param serviceClass class that defines retrofit client service.
     */
    public RetrofitMeterIdPrefixFunctionBuilder serviceClass(Class<?> serviceClass) {
        this.serviceClass = serviceClass;
        return this;
    }

    /**
     * Returns a newly created {@link RetrofitMeterIdPrefixFunction} with the properties specified so far.
     */
    public RetrofitMeterIdPrefixFunction build() {
        if (serviceClass == null) {
            return new RetrofitMeterIdPrefixFunction(name, serviceTagName, serviceName, defaultServiceName);
        }
        return new RetrofitClassAwareMeterIdPrefixFunction(name, serviceTagName, serviceName, serviceClass);
    }
}
