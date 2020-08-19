/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

/**
 * A holder of a value of an {@link AbstractOption}.
 *
 * @param <T> the type of the option value holder.
 * @param <U> the type of the option.
 * @param <V> the type of the option value.
 *
 * @see AbstractOption
 * @see AbstractOptions
 */
public abstract class AbstractOptionValue<
        T extends AbstractOptionValue<T, U, V>,
        U extends AbstractOption<U, T, V>,
        V> {

    private final U option;
    private final V value;

    /**
     * Creates a new instance with the specified {@code option} and {@code value}.
     */
    protected AbstractOptionValue(U option, V value) {
        this.option = requireNonNull(option, "option");
        this.value = requireNonNull(value, "value");
    }

    /**
     * Returns the option that this option value holder belongs to.
     */
    public final U option() {
        return option;
    }

    /**
     * Returns the value of this option value holder has.
     */
    public final V value() {
        return value;
    }

    @Override
    public final String toString() {
        return option.toString() + '=' + value;
    }
}
