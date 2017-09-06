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
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.server.tomcat;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.webresources.JarResourceSet;

final class JarSubsetResourceSet extends JarResourceSet {

    private final String prefix;

    JarSubsetResourceSet(WebResourceRoot root, String webAppMount, String base, String internalPath,
                         String jarRoot) {
        super(root, webAppMount, base, internalPath);

        // Should be normalized by TomcatServiceBuilder.
        assert !"/".equals(jarRoot) : "JarResourceSet should be used instead.";
        assert jarRoot.startsWith("/") : "jarRoot must be absolute.";
        assert !jarRoot.endsWith("/") : "jarRoot must not end with '/'.";

        prefix = jarRoot.substring(1) + '/';
    }

    @Override
    protected HashMap<String,JarEntry> getArchiveEntries(boolean single) {
        synchronized (archiveLock) {
            if (archiveEntries == null && !single) {
                JarFile jarFile = null;
                archiveEntries = new HashMap<>();
                try {
                    jarFile = openJarFile();
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        final JarEntry entry = entries.nextElement();
                        final String name = entry.getName();
                        if (!name.startsWith(prefix)) {
                            continue;
                        }

                        archiveEntries.put(name.substring(prefix.length()), entry);
                    }
                } catch (IOException ioe) {
                    // Should never happen
                    archiveEntries = null;
                    throw new IllegalStateException(ioe);
                } finally {
                    if (jarFile != null) {
                        closeJarFile();
                    }
                }
            }
            return archiveEntries;
        }
    }

    @Override
    protected JarEntry getArchiveEntry(String pathInArchive) {
        JarFile jarFile = null;
        try {
            jarFile = openJarFile();
            return jarFile.getJarEntry(prefix + pathInArchive);
        } catch (IOException ioe) {
            // Should never happen
            throw new IllegalStateException(ioe);
        } finally {
            if (jarFile != null) {
                closeJarFile();
            }
        }
    }
}
