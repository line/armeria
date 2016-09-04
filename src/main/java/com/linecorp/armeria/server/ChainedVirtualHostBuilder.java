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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

/**
 * Build a new {@link VirtualHost}.
 * This class can only be created through 
 * the withDefaultVirtualHost() or withVirtualHost() method of the serverBuilder.
 * call and() method can also return to ServerBuilder.
 * 
 * @see ServerBuilder
 * @see PathMapping
 * @see VirtualHostBuilder
 */
public final class ChainedVirtualHostBuilder extends AbstractVirtualHostBuilder<ChainedVirtualHostBuilder> {

    private final ServerBuilder serverBuilder;
    
    /**
     * Creates a new {@link VirtualHostBuilder} whose hostname pattern is {@code "*"} (match-all).
     * 
     * @param serverBuilder parent {@link ServerBuilder} for return
     */
    ChainedVirtualHostBuilder(ServerBuilder serverBuilder) {
        super(LOCAL_HOSTNAME, "*");
        
        requireNonNull(serverBuilder, "serverBuilder");
        this.serverBuilder = serverBuilder;
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with the specified hostname pattern.
     * 
     * @param hostnamePattern virtual host name regular expression
     * @param serverBuilder parent {@link ServerBuilder} for return
     */
    ChainedVirtualHostBuilder(String hostnamePattern, ServerBuilder serverBuilder) {
        super(hostnamePattern);
        
        requireNonNull(serverBuilder, "serverBuilder");
        this.serverBuilder = serverBuilder;
    }

    ChainedVirtualHostBuilder(String defaultHostname, String hostnamePattern, ServerBuilder serverBuilder) {
        super(defaultHostname, hostnamePattern);
        
        requireNonNull(serverBuilder, "serverBuilder");
        this.serverBuilder = serverBuilder;
    }

    /**
     * Return parent serverBuilder.
     * 
     * @return serverBuiler
     */
    public ServerBuilder and() {
        return serverBuilder;
    }
}
