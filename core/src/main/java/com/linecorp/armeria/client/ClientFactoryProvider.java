/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Creates a new {@link ClientFactory} dynamically via Java SPI (Service Provider Interface).
 */
@FunctionalInterface
public interface ClientFactoryProvider {
    /**
     * Creates a new {@link ClientFactory}.
     */
    ClientFactory newFactory(SessionOptions options, Map<Class<?>, ClientFactory> dependencies);

    /**
     * Returns the type of the {@link ClientFactory} required for this provider to create a new
     * {@link ClientFactory}. The {@link Map} which is given when
     * {@link #newFactory(SessionOptions, Map)} is invoked will contain the entries
     * for the classes returned by this method and their respective instances.
     */
    default Set<Class<? extends ClientFactory>> dependencies() {
        return Collections.emptySet();
    }
}
