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

package com.linecorp.armeria.server.http.tomcat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.catalina.Context;
import org.apache.catalina.WebResourceSet;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.webresources.StandardRoot;

/**
 * A {@link StandardRoot} that accepts any ZIP-based archive files.
 */
final class ArmeriaWebResourceRoot extends StandardRoot {

    private final TomcatServiceConfig config;

    ArmeriaWebResourceRoot(Context context, TomcatServiceConfig config) {
        super(context);
        this.config = config;
    }

    @Override
    protected WebResourceSet createMainResourceSet() {
        final Path docBase = config.docBase();
        assert docBase.isAbsolute();

        final String docBaseStr = docBase.toString();
        getContext().setDocBase(docBaseStr);

        if (Files.isDirectory(docBase)) {
            return new DirResourceSet(this, "/", docBaseStr, "/");
        }

        final Optional<String> jarRootOpt = config.jarRoot();
        if (jarRootOpt.isPresent()) { // If docBase is a JAR file
            final String jarRoot = jarRootOpt.get();
            if ("/".equals(jarRoot)) {
                return new JarResourceSet(this, "/", docBaseStr, "/");
            } else {
                return new JarSubsetResourceSet(this, "/", docBaseStr, "/", jarRoot);
            }
        }

        throw new IllegalArgumentException(sm.getString("standardRoot.startInvalidMain", docBaseStr));
    }
}
