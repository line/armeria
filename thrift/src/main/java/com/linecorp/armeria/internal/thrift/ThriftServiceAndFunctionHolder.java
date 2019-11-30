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

package com.linecorp.armeria.internal.thrift;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

/**
 * Holds the {@link ThriftFunction} and the implementation for a given function name.
 */
public final class ThriftServiceAndFunctionHolder {

    private final ThriftFunction function;

    private final String name;

    @Nullable
    private final Object implementation;

    ThriftServiceAndFunctionHolder(String name, ThriftFunction function, @Nullable Object implementation) {
        requireNonNull(function, "function");
        requireNonNull(name, "name");

        this.function = function;
        this.implementation = implementation;
        this.name = name;
    }

    public ThriftFunction function() {
        return function;
    }

    @Nullable
    public Object implementation() {
        return implementation;
    }

    public String getName() {
        return name;
    }
}
