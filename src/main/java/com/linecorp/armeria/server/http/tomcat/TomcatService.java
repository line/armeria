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

import com.linecorp.armeria.server.http.HttpService;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded Tomcat.
 */
public class TomcatService extends HttpService {

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatService forCurrentClassPath() {
        return TomcatServiceBuilder.forCurrentClassPath(3).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatService forCurrentClassPath(String docBase) {
        return TomcatServiceBuilder.forCurrentClassPath(docBase, 3).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatService forClassPath(Class<?> clazz) {
        return TomcatServiceBuilder.forClassPath(clazz).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatService forClassPath(Class<?> clazz, String docBase) {
        return TomcatServiceBuilder.forClassPath(clazz, docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService forFileSystem(String docBase) {
        return TomcatServiceBuilder.forFileSystem(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService forFileSystem(Path docBase) {
        return TomcatServiceBuilder.forFileSystem(docBase).build();
    }

    TomcatService(TomcatServiceConfig config) {
        super(new TomcatServiceInvocationHandler(config));
    }

    /**
     * Returns the configuration.
     */
    public TomcatServiceConfig config() {
        return ((TomcatServiceInvocationHandler) handler()).config();
    }
}
