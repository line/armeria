/*
 * Copyright 2015 LINE Corporation
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
 * A {@link Service} and its {@link PathMapping} and {@link VirtualHost}.
 *
 * @see ServerConfig#services()
 * @see VirtualHost#services()
 */
public final class ServiceEntry {

    private final VirtualHost virtualHost;
    private final PathMapping pathMapping;
    private final Service service;

    /**
     * Creates a new instance.
     */
    public ServiceEntry(VirtualHost virtualHost, PathMapping pathMapping, Service service) {
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.service = requireNonNull(service, "service");
    }

    /**
     * Returns the {@link VirtualHost} the {@link #service()} belongs to.
     */
    public VirtualHost virtualHost() {
        return virtualHost;
    }

    /**
     * Returns the {@link PathMapping} of the {@link #service()}.
     */
    public PathMapping pathMapping() {
        return pathMapping;
    }

    /**
     * Returns the {@link Service}.
     */
    public Service service() {
        return service;
    }

    @Override
    public String toString() {
        return "{ " + virtualHost.hostnamePattern() + ", " + pathMapping + ", " + service + " }";
    }
}
