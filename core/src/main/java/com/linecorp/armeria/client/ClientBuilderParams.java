/*
 * Copyright 2016 LINE Corporation
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

import java.net.URI;

/**
 * Provides the construction parameters of a client.
 */
public interface ClientBuilderParams {
    /**
     * Returns the {@link ClientFactory} who created the client.
     */
    ClientFactory factory();

    /**
     * Returns the endpoint URI of the client.
     */
    URI uri();

    /**
     * Returns the type of the client.
     */
    Class<?> clientType();

    /**
     * Returns the options of the client.
     */
    ClientOptions options();
}
