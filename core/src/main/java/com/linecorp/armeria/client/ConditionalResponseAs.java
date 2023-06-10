/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Predicate;

public class ConditionalResponseAs<T, R, V> {

    private final ResponseAs<T, R> originalResposeAs;
    ConditionalResponseAs (ResponseAs<T, R> originalResposeAs, ResponseAs<R, V> responseAs, Predicate<R> predicate) {
        this.originalResposeAs = originalResposeAs;
        // This is necessary to add predicate into list
        andThen(responseAs, predicate);
    }

    final List<Entry<ResponseAs<R, V>, Predicate<R>>> list = new ArrayList<>();

    public ResponseAs<T, V> orElse(ResponseAs<R, V> responseAs) {
        return new ResponseAs<T, V>() {
            @Override
            public V as(T response) {
                final R r = originalResposeAs.as(response);
                for (Entry<ResponseAs<R, V>, Predicate<R>> entry: list) {
                    if (entry.getValue().test(r)) {
                        return entry.getKey().as(r);
                    }
                }
                return responseAs.as(r);
            }
        };
    }

    public ConditionalResponseAs<T, R, V> andThen(ResponseAs<R, V> responseAs, Predicate<R> predicate) {
        list.add(new SimpleEntry<>(responseAs, predicate));
        return this;
    }
}
