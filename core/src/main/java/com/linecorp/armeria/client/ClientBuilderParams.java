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

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides the construction parameters of a client.
 */
public interface ClientBuilderParams {

    /**
     * Returns a newly created {@link ClientBuilderParams} from the specified properties.
     */
    static ClientBuilderParams of(URI uri, Class<?> type, ClientOptions options) {
        requireNonNull(uri, "uri");
        requireNonNull(type, "type");
        requireNonNull(options, "options");
        return new DefaultClientBuilderParams(uri, type, options);
    }

    /**
     * Returns a newly created {@link ClientBuilderParams} from the specified properties.
     */
    static ClientBuilderParams of(Scheme scheme, EndpointGroup endpointGroup,
                                  @Nullable String absolutePathRef, Class<?> type, ClientOptions options) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(type, "type");
        requireNonNull(options, "options");
        return new DefaultClientBuilderParams(scheme, endpointGroup, absolutePathRef, type, options);
    }

    /**
     * Returns the {@link Scheme} of the client.
     */
    Scheme scheme();

    /**
     * Returns the {@link EndpointGroup} of the client.
     */
    EndpointGroup endpointGroup();

    /**
     * Returns the {@link String} that consists of path, query string and fragment.
     */
    String absolutePathRef(); // Name inspired by https://stackoverflow.com/a/47545070/55808

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

    /**
     * Returns a {@link ClientBuilderParamsBuilder} which allows creation of a new
     * {@link ClientBuilderParams} based on the current properties.
     */
    default ClientBuilderParamsBuilder paramsBuilder() {
        return new ClientBuilderParamsBuilder(this);
    }
}
