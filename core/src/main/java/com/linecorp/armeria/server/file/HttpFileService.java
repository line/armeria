/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.file;

import java.nio.file.Path;

import com.linecorp.armeria.server.HttpService;

/**
 * An {@link HttpService} that serves static files from a file system.
 *
 * @deprecated Use {@link FileService}.
 */
@Deprecated
public final class HttpFileService extends FileService {

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     *
     * @deprecated Use {@link FileService#of(Path)}.
     */
    @Deprecated
    public static HttpFileService forFileSystem(String rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     *
     * @deprecated Use {@link FileService#of(Path)}.
     */
    @Deprecated
    public static HttpFileService forFileSystem(Path rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     *
     * @deprecated Use {@link FileService#of(ClassLoader, String)}.
     */
    @Deprecated
    public static HttpFileService forClassPath(String rootDir) {
        return HttpFileServiceBuilder.forClassPath(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     *
     * @deprecated Use {@link FileService#of(ClassLoader, String)}.
     */
    @Deprecated
    public static HttpFileService forClassPath(ClassLoader classLoader, String rootDir) {
        return HttpFileServiceBuilder.forClassPath(classLoader, rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@link HttpVfs}.
     *
     * @deprecated Use {@link FileService#of(HttpVfs)}.
     */
    @Deprecated
    public static HttpFileService forVfs(HttpVfs vfs) {
        return HttpFileServiceBuilder.forVfs(vfs).build();
    }

    HttpFileService(FileServiceConfig config) {
        super(config);
    }
}
