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

package com.linecorp.armeria.server.http.tomcat;

import java.nio.file.Path;

import org.apache.catalina.Realm;

/**
 * {@link TomcatService} configuration.
 */
public class TomcatServiceConfig {

    private final Path baseDir;
    private final Realm realm;
    private final String hostname;
    private final Path docBase;

    TomcatServiceConfig(Path baseDir, Realm realm, String hostname, Path docBase) {

        this.baseDir = baseDir;
        this.realm = realm;
        this.hostname = hostname;
        this.docBase = docBase;
    }

    /**
     * Returns the base directory of an embedded Tomcat.
     */
    public Path baseDir() {
        return baseDir;
    }

    /**
     * Returns the {@link Realm} of an embedded Tomcat.
     */
    public Realm realm() {
        return realm;
    }

    /**
     * Returns the hostname of an embedded Tomcat.
     */
    public String hostname() {
        return hostname;
    }

    /**
     * Returns the document base directory of an web application.
     */
    public Path docBase() {
        return docBase;
    }

    @Override
    public String toString() {
        return toString(this, baseDir(), realm(), hostname(), docBase());
    }

    static String toString(Object holder, Path baseDir, Realm realm, String hostname, Path docBase) {

        return holder.getClass().getSimpleName() +
               "(baseDir: " + baseDir +
               ", realm: " + realm.getClass().getSimpleName() +
               ", hostname: " + hostname +
               ", docBase: " + docBase + ')';
    }
}
