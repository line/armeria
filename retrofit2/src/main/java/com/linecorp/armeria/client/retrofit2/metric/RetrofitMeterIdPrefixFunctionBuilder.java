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
package com.linecorp.armeria.client.retrofit2.metric;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

public final class RetrofitMeterIdPrefixFunctionBuilder {

    /**
     * Creates a {@link RetrofitMeterIdPrefixFunctionBuilder} with {@code name}.
     */
    public static RetrofitMeterIdPrefixFunctionBuilder ofName(String name) {
        return new RetrofitMeterIdPrefixFunctionBuilder(requireNonNull(name, "name"));
    }

    /**
     * Creates a {@link RetrofitMeterIdPrefixFunctionBuilder} with name starts with "armeria.".
     */
    public static RetrofitMeterIdPrefixFunctionBuilder ofType(String type) {
        return new RetrofitMeterIdPrefixFunctionBuilder("armeria." + requireNonNull(type, "type"));
    }

    private final String name;
    @Nullable
    private String tagName;
    @Nullable
    private String defaultServiceName;

    private RetrofitMeterIdPrefixFunctionBuilder(String name) {
        this.name = name;
    }

    /**
     * Make tag of {@link RetrofitMeterIdPrefixFunction} contains Retrofit service interface name.
     */
    public RetrofitMeterIdPrefixFunctionBuilder withServiceTag(String tagName, String defaultServiceName) {
        this.tagName = tagName;
        this.defaultServiceName = defaultServiceName;
        return this;
    }

    public RetrofitMeterIdPrefixFunction build() {
        return new RetrofitMeterIdPrefixFunction(name, tagName, defaultServiceName);
    }
}
