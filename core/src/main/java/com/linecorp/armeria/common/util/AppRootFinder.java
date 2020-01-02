/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/**
 * A utility that looks for the {@link Path} to the JAR, WAR or directory where a {@link Class} is located at.
 */
public final class AppRootFinder {

    /**
     * Returns the {@link Path} to the JAR, WAR or directory where the caller class of this method is located
     * at. This method is a shortcut for {@code findCurrent(0)}.
     */
    public static Path findCurrent() {
        // Increase the call depth by 1 because we delegate.
        return findCurrent(1);
    }

    /**
     * Returns the {@link Path} to the JAR, WAR or directory where the caller class of this method is located
     * at.
     *
     * @param callDepth how many calls were made between the caller class and this {@link #findCurrent(int)}.
     */
    public static Path findCurrent(int callDepth) {
        final AtomicReference<Class<?>[]> classContextRef = new AtomicReference<>();
        new SecurityManager() {
            {
                classContextRef.set(getClassContext());
            }
        };

        // Skip the first two classes which are:
        // - This class
        // - The anonymous SecurityManager
        final Class<?>[] classes = classContextRef.get();
        final int toSkip = 2;

        if (callDepth < 0 || callDepth + toSkip >= classes.length) {
            throw new IndexOutOfBoundsException(
                    "callDepth out of range: " + callDepth +
                    " (expected: 0 <= callDepth < " + (classes.length - toSkip) + ')');
        }

        return find(classes[toSkip + callDepth]);
    }

    /**
     * Returns the {@link Path} to the JAR, WAR or directory where the specified {@link Class} is located at.
     */
    public static Path find(Class<?> clazz) {
        requireNonNull(clazz, "clazz");

        final ProtectionDomain pd = clazz.getProtectionDomain();
        if (pd == null) {
            throw new IllegalArgumentException(clazz + " does not have a protection domain.");
        }
        final CodeSource cs = pd.getCodeSource();
        if (cs == null) {
            throw new IllegalArgumentException(clazz + " does not have a code source.");
        }
        final URL url = cs.getLocation();
        if (url == null) {
            throw new IllegalArgumentException(clazz + " does not have a location.");
        }

        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException(clazz + " is not on a file system: " + url);
        }

        File f;
        try {
            f = new File(url.toURI());
        } catch (URISyntaxException ignored) {
            f = new File(url.getPath());
        }

        final Path path = f.toPath();
        if (!Files.isDirectory(path) && !isZip(path)) {
            throw new IllegalArgumentException(f + " is not a JAR, WAR or directory.");
        }

        return path;
    }

    private static boolean isZip(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }

        try (ZipFile ignored = new ZipFile(path.toFile())) {
            return true;
        } catch (IOException ignored) {
            // Probably not a JAR file.
            return false;
        }
    }

    private AppRootFinder() {}
}
