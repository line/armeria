/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.common;

import java.util.function.Function;

import com.google.common.base.MoreObjects;

/**
 * A class to hold a decorator with its order.
 */
public class DecoratorAndOrder<T> implements Comparable<DecoratorAndOrder<T>> {

    public static final int DEFAULT_ORDER = 0;

    private final Function<? super T, ? extends T> decorator;
    private final int order;

    public DecoratorAndOrder(Function<? super T, ? extends T> decorator, int order) {
        this.decorator = decorator;
        this.order = order;
    }

    /**
     * Returns the {@code decorator}.
     */
    public Function<? super T, ? extends T> decorator() {
        return decorator;
    }

    /**
     * Returns the {@code order}.
     */
    public int order() {
        return order;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("decorator", decorator())
                          .add("order", order())
                          .toString();
    }

    @Override
    public int compareTo(DecoratorAndOrder decoratorAndOrder) {
        return Integer.compare(decoratorAndOrder.order(), order());
    }
}
