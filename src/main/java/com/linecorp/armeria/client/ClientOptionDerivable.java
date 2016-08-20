/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Arrays;

/**
 * A client that allows creating a new client with alternative {@link ClientOption}s.
 *
 * @param <T> self type
 */
@FunctionalInterface
public interface ClientOptionDerivable<T> {

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * with the specified {@code additionalOptions}. Note that the derived client will use the options of
     * the specified {@code client} unless specified in {@code additionalOptions}.
     */
    default T withOptions(ClientOptionValue<?>... additionalOptions) {
        requireNonNull(additionalOptions, "additionalOptions");
        return withOptions(Arrays.asList(additionalOptions));
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * with the specified {@code additionalOptions}. Note that the derived client will use the options of
     * the specified {@code client} unless specified in {@code additionalOptions}.
     */
    T withOptions(Iterable<ClientOptionValue<?>> additionalOptions);
}
