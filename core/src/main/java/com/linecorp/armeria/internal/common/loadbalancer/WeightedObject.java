/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.common.loadbalancer;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.loadbalancer.Weighted;

public class WeightedObject<T> implements Weighted {
    private final T element;

    private final int weight;

    public WeightedObject(T element, int weight) {
        this.element = element;
        this.weight = weight;
    }

    public final T get() {
        return element;
    }

    @Override
    public final int weight() {
        return weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WeightedObject)) {
            return false;
        }
        final WeightedObject<?> weighted = (WeightedObject<?>) o;
        return weight == weighted.weight && element.equals(weighted.element);
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, weight);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("element", element)
                          .add("weight", weight)
                          .toString();
    }
}
