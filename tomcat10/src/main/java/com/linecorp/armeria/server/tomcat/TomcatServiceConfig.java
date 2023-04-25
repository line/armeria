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

package com.linecorp.armeria.server.tomcat;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.apache.catalina.Realm;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * {@link TomcatService} configuration.
 */
final class TomcatServiceConfig {

    private final String serviceName;
    @Nullable
    private final String engineName;
    private final Path baseDir;
    private final Realm realm;
    @Nullable
    private final String hostname;
    private final Path docBase;
    @Nullable
    private final String jarRoot;
    private final List<Consumer<? super StandardServer>> configurators;

    TomcatServiceConfig(String serviceName, @Nullable String engineName, Path baseDir, Realm realm,
                        @Nullable String hostname, Path docBase, @Nullable String jarRoot,
                        List<Consumer<? super StandardServer>> configurators) {

        this.engineName = engineName;
        this.serviceName = serviceName;
        this.baseDir = baseDir;
        this.realm = realm;
        this.hostname = hostname;
        this.docBase = docBase;
        this.jarRoot = jarRoot;
        this.configurators = configurators;
    }

    /**
     * Returns the name of the {@link StandardService} of an embedded Tomcat.
     */
    String serviceName() {
        return serviceName;
    }

    /**
     * Returns the name of the {@link StandardEngine} of an embedded Tomcat.
     */
    String engineName() {
        return MoreObjects.firstNonNull(engineName, serviceName);
    }

    /**
     * Returns the base directory of an embedded Tomcat.
     */
    Path baseDir() {
        return baseDir;
    }

    /**
     * Returns the {@link Realm} of an embedded Tomcat.
     */
    Realm realm() {
        return realm;
    }

    /**
     * Returns the hostname of an embedded Tomcat.
     */
    @Nullable
    String hostname() {
        return hostname;
    }

    /**
     * Returns the document base directory of a web application.
     */
    Path docBase() {
        return docBase;
    }

    /**
     * Returns the path to the root directory of a web application inside a JAR/WAR. {@code null} will be
     * returned if {@link #docBase()} does not refer to a JAR/WAR file.
     */
    @Nullable
    String jarRoot() {
        return jarRoot;
    }

    /**
     * Returns the {@link Consumer}s that performs additional configuration operations against
     * the Tomcat {@link StandardServer} created by a {@link TomcatService}.
     */
    List<Consumer<? super StandardServer>> configurators() {
        return configurators;
    }

    @Override
    public String toString() {
        return toString(this, serviceName(), engineName(), baseDir(), realm(), hostname(),
                        docBase(), jarRoot());
    }

    static String toString(Object holder, String serviceName, @Nullable String engineName,
                           @Nullable Path baseDir, @Nullable Realm realm, @Nullable String hostname,
                           Path docBase, @Nullable String jarRoot) {

        return holder.getClass().getSimpleName() +
               "(serviceName: " + serviceName +
               ", engineName: " + engineName +
               ", baseDir: " + baseDir +
               ", realm: " + (realm != null ? realm.getClass().getSimpleName() : "null") +
               ", hostname: " + hostname +
               ", docBase: " + docBase +
               (jarRoot != null ? ", jarRoot: " + jarRoot : "") +
               ')';
    }
}
