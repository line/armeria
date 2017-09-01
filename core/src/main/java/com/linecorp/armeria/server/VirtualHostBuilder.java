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

package com.linecorp.armeria.server;

/**
 * Builds a new {@link VirtualHost}.
 * <h2>Example</h2>
 * <pre>{@code
 * VirtualHostBuilder vhb = new VirtualHostBuilder("*.example.com");
 * vhb.service("/foo", new FooService())
 *    .serviceUnder("/bar/", new BarService())
 *    .service(PathMapping.ofRegex("^/baz/.*", new BazService());
 *
 * VirtualHost vh = vhb.build();
 * }</pre>
 *
 * @see PathMapping
 */
public class VirtualHostBuilder extends AbstractVirtualHostBuilder<VirtualHostBuilder> {

    /**
     * Creates a new {@link VirtualHostBuilder} whose hostname pattern is {@code "*"} (match-all).
     */
    public VirtualHostBuilder() {
        super();
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with the specified hostname pattern.
     */
    public VirtualHostBuilder(String hostnamePattern) {
        super(hostnamePattern);
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with
     * the default host name and the specified hostname pattern.
     */
    public VirtualHostBuilder(String defaultHostname, String hostnamePattern) {
        super(defaultHostname, hostnamePattern);
    }

    @Override
    public VirtualHost build() {
        return super.build();
    }
}
