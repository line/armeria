/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.tomcat;

import java.util.Optional;

import javax.annotation.Nullable;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

class UnmanagedTomcatService extends TomcatService {

    @Nullable
    private final String hostName;
    private final Optional<Tomcat> tomcat;
    private final Optional<Connector> connector;

    UnmanagedTomcatService(Tomcat tomcat) {
        this.hostName = null;
        this.tomcat = Optional.of(tomcat);
        this.connector = Optional.empty();
    }

    UnmanagedTomcatService(@Nullable String hostName, Connector connector) {
        this.hostName = hostName;
        this.tomcat = Optional.empty();
        this.connector = Optional.of(connector);
    }

    @Override
    public Optional<Connector> connector() {
        if (connector.isPresent()) {
            return connector;
        }

        return tomcat.map(Tomcat::getConnector);
    }

    @Override
    public String hostName() {
        if (hostName != null) {
            return hostName;
        }

        // If connector w/o hostName
        return tomcat.map(t -> t.getEngine().getDefaultHost())
                     .orElse(null);
    }
}
