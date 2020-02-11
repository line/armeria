/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.reflections.Configuration;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Resources;

import com.linecorp.armeria.common.util.UnstableApi;

/**
 * A supporting base class for implementing the standard pattern of extracting docstrings
 * from arbitrary files in a particular classpath location.
 */
@UnstableApi
public abstract class DocStringExtractor {

    private static final Logger logger = LoggerFactory.getLogger(DocStringExtractor.class);

    private static final Map<ClassLoader, Map<String, String>> cached = new ConcurrentHashMap<>();

    private final String path;

    protected DocStringExtractor(String defaultPath, String pathPropertyName) {
        path = computePath(defaultPath, pathPropertyName);
    }

    /**
     * Extract all docstrings from files at the configured path, delegating to
     * {@link #getDocStringsFromFiles(Map)} for actual processing.
     */
    public Map<String, String> getAllDocStrings(ClassLoader classLoader) {
        requireNonNull(classLoader, "classLoader");
        return cached.computeIfAbsent(classLoader, this::getAllDocStrings0);
    }

    private Map<String, String> getAllDocStrings0(ClassLoader classLoader) {
        final Configuration configuration = new ConfigurationBuilder()
                .filterInputsBy(new FilterBuilder().includePackage(path))
                .setUrls(ClasspathHelper.forPackage(path, classLoader))
                .addClassLoader(classLoader)
                .setScanners(new ResourcesScanner());
        if (configuration.getUrls() == null || configuration.getUrls().isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, byte[]> files = new Reflections(configuration)
                .getResources(this::acceptFile).stream()
                .map(f -> {
                    try {
                        final URL url = classLoader.getResource(f);
                        if (url == null) {
                            throw new IllegalStateException("not found: " + f);
                        }
                        return new SimpleImmutableEntry<>(f, Resources.toByteArray(url));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));
        return getDocStringsFromFiles(files);
    }

    /**
     * Determine whether the file at {@code filename} should be processed by the {@link DocStringExtractor}.
     * This will usually look at the file extension, but the default implementation can be used to check all
     * files at a particular path.
     */
    protected boolean acceptFile(String filename) {
        return true;
    }

    /**
     * Extracts a {@link Map} of docstrings from the given {@link Map} of path to file contents. The result will
     * generally be used within a {@link DocServicePlugin} for finding docstrings of items.
     */
    protected abstract Map<String, String> getDocStringsFromFiles(Map<String, byte[]> files);

    private static String computePath(String defaultPath, String pathPropertyName) {
        String dir = System.getProperty(pathPropertyName, defaultPath);
        if (dir.startsWith("/") || dir.startsWith("\\")) {
            dir = dir.substring(1);
        }
        if (dir.endsWith("/") || dir.endsWith("\\")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        logger.info("Using {}: {}", pathPropertyName, dir);
        return dir;
    }
}
