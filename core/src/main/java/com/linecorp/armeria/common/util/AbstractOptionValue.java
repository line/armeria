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
 * @param <O> the {@link AbstractOption} that this option value is created by
 * @param <V> the type of the value of the option {@code 'O'}
 *
 * @see AbstractOption
 * @see AbstractOptions
 */
public abstract class AbstractOptionValue<O extends AbstractOption<V>, V> {

    private final O option;
    private final V value;

    /**
     * Creates a new instance with the specified {@code option} and {@code value}.
     */
    protected AbstractOptionValue(O option, V value) {
        this.option = requireNonNull(option, "option");
        this.value = requireNonNull(value, "value");
    }

    /**
     * Returns the option that this option value holder belongs to.
     */
    public O option() {
        return option;
    }

    /**
     * Returns the value of this option value holder has.
     */
    public V value() {
        return value;
    }

    @Override
    public String toString() {
        return option.toString() + '=' + value;
    }
}
