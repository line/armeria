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

package com.linecorp.armeria.server.http.file;

import java.nio.file.Path;

import com.linecorp.armeria.server.http.HttpService;

/**
 * An {@link HttpService} that serves static files from a file system.
 *
 * @see HttpFileServiceBuilder
 */
public final class HttpFileService extends HttpService {

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileService forFileSystem(String rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileService forFileSystem(Path rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     */
    public static HttpFileService forClassPath(String rootDir) {
        return HttpFileServiceBuilder.forClassPath(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     */
    public static HttpFileService forClassPath(ClassLoader classLoader, String rootDir) {
        return HttpFileServiceBuilder.forClassPath(classLoader, rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@link HttpVfs}.
     */
    public static HttpFileService forVfs(HttpVfs vfs) {
        return HttpFileServiceBuilder.forVfs(vfs).build();
    }

    HttpFileService(HttpFileServiceConfig config) {
        super(new HttpFileServiceInvocationHandler(config));
    }

    /**
     * Returns the configuration.
     */
    public HttpFileServiceConfig config() {
        return ((HttpFileServiceInvocationHandler) handler()).config();
    }
}
