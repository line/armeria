/*
 * Copyright 2021 LINE Corporation
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

import java.util.function.IntSupplier;

import com.linecorp.armeria.client.limit.ConcurrencyLimit;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Supplies the cached value that is useful below cases.
 * <p> - Supplies dynamically a maximum number of concurrent active requests for {@link ConcurrencyLimit}.
 * For example:
 * <pre>{@code
 * class DynamicSupplier extends SettableIntSupplier {
 *   public DynamicSupplier() {
 *     super(10); //set initial value
 *     AnyListener<Integer> listener = ...
 *     listener.addListener(this::set);
 *   }
 * }}</pre>
 */
@UnstableApi
public class SettableIntSupplier implements IntSupplier {
    private volatile int value;

    /**
     * Creates a new instance with the specified {@code initialValue}.
     */
    public static <T> SettableIntSupplier of(int initialValue) {
        return new SettableIntSupplier(initialValue);
    }

    protected SettableIntSupplier(int initialValue) {
        value = initialValue;
    }

    /**
     * Returns the cached value.
     */
    @Override
    public final int getAsInt() {
        return value;
    }

    /**
     * Caches a value.
     */
    public final void set(int value) {
        this.value = value;
    }
}
