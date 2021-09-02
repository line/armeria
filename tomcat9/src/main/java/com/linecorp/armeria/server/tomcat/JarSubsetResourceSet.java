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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.webresources.AbstractArchiveResourceSet;
import org.apache.catalina.webresources.JarResourceSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

final class JarSubsetResourceSet extends JarResourceSet {

    // We cannot access AbstractArchiveResourceSet.archiveEntries directly because:
    // - Tomcat 8.0 and 8.5 declared it as HashMap
    // - Tomcat 9.0 declared it as Map
    // Therefore, when compiled with Tomcat 9.0, this code will fail with a NoSuchFieldError when it attempts
    // to access archiveEntries with Tomcat 8.x.
    private static final MethodHandle archiveEntriesGetter;
    private static final MethodHandle archiveEntriesSetter;

    static {
        @Nullable MethodHandle getter = null;
        @Nullable MethodHandle setter = null;
        try {
            // No need to call setAccessible() because this class is a subclass and it's a protected field.
            final Field field = AbstractArchiveResourceSet.class.getDeclaredField("archiveEntries");
            getter = MethodHandles.lookup().unreflectGetter(field);
            setter = MethodHandles.lookup().unreflectSetter(field);
        } catch (Exception e) {
            Exceptions.throwUnsafely(e);
        }

        assert getter != null;
        assert setter != null;
        archiveEntriesGetter = getter;
        archiveEntriesSetter = setter;
    }

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

    @Nullable
    @Override
    protected HashMap<String,JarEntry> getArchiveEntries(boolean single) {
        synchronized (archiveLock) {
            @Nullable Map<String, JarEntry> archiveEntries = mhGetArchiveEntries();
            if (archiveEntries == null && !single) {
                @Nullable JarFile jarFile = null;
                mhSetArchiveEntries(archiveEntries = new HashMap<>());
                try {
                    jarFile = openJarFile();
                    final Enumeration<JarEntry> entries = jarFile.entries();
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
                    mhSetArchiveEntries(null);
                    throw new IllegalStateException(ioe);
                } finally {
                    if (jarFile != null) {
                        closeJarFile();
                    }
                }
            }

            if (archiveEntries == null) {
                return null;
            } else if (archiveEntries instanceof HashMap) {
                return (HashMap<String, JarEntry>) archiveEntries;
            } else {
                return new HashMap<>(archiveEntries);
            }
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private Map<String, JarEntry> mhGetArchiveEntries() {
        try {
            return (Map<String, JarEntry>) archiveEntriesGetter.invoke(this);
        } catch (Throwable t) {
            throw new Error(t);
        }
    }

    private void mhSetArchiveEntries(@Nullable Map<String, JarEntry> archiveEntries) {
        try {
            archiveEntriesSetter.invoke(this, archiveEntries);
        } catch (Throwable t) {
            throw new Error(t);
        }
    }

    @Override
    protected JarEntry getArchiveEntry(String pathInArchive) {
        @Nullable JarFile jarFile = null;
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
