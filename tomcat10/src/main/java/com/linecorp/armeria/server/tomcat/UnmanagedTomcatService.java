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

import static java.util.Objects.requireNonNull;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import com.linecorp.armeria.common.annotation.Nullable;

final class UnmanagedTomcatService extends TomcatService {

    @Nullable
    private final String hostName;
    @Nullable
    private final Tomcat tomcat;
    @Nullable
    private final Connector connector;

    UnmanagedTomcatService(Tomcat tomcat) {
        hostName = null;
        this.tomcat = requireNonNull(tomcat, "tomcat");
        connector = null;
    }

    UnmanagedTomcatService(Connector connector, @Nullable String hostName) {
        this.hostName = hostName;
        tomcat = null;
        this.connector = requireNonNull(connector, "connector");
    }

    @Override
    public Connector connector() {
        if (connector != null) {
            return connector;
        }

        assert tomcat != null;
        return tomcat.getConnector();
    }

    @Override
    public String hostName() {
        if (hostName != null) {
            return hostName;
        }

        // If connector w/o hostName
        if (tomcat != null) {
            return tomcat.getEngine().getDefaultHost();
        } else {
            return null;
        }
    }
}
