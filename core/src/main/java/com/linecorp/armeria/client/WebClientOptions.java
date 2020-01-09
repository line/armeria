/*
 * Copyright 2020 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.client.endpoint.EndpointGroup;

/**
 * {@link ClientOption}s to control {@link WebClient}-specific behavior.
 */
public final class WebClientOptions {

    /**
     * A {@link Function} that remaps an {@link Endpoint} or an absolute URL's authority into
     * an {@link EndpointGroup}. This {@link ClientOption} is useful when you need to override a single
     * target host into a group of hosts to enable client-side load-balancing, e.g.
     * <pre>{@code
     * EndpointGroup myGroup = EndpointGroup.of(Endpoint.of("node-1.my-group.com")),
     *                                          Endpoint.of("node-2.my-group.com")));
     * WebClient client = WebClient.builder()
     *                             .endpointRemapper(endpoint -> {
     *                                 if (endpoint.host().equals("my-group.com")) {
     *                                     return myGroup;
     *                                 } else {
     *                                     return endpoint;
     *                                 }
     *                             })
     *                             .build();
     *
     * // This request goes to 'node-1.my-group.com' or 'node-2.my-group.com'.
     * HttpResponse res = client.get("http://my-group.com/");
     * }</pre>
     *
     * <p>Note that the remapping does not occur recursively but only once.</p>
     *
     * @see WebClientBuilder#endpointRemapper(Function)
     */
    public static final ClientOption<Function<? super Endpoint, ? extends EndpointGroup>> ENDPOINT_REMAPPER =
            ClientOption.valueOf("ENDPOINT_REMAPPER");

    private WebClientOptions() {}
}
