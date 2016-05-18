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

package com.linecorp.armeria.server.docs;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reflections.Configuration;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

/**
 * {@link ThriftDocString} is a DocString extractor for Thrift IDL JSON.
 *
 * To include docstrings in {@link DocService} pages, use a recent development version of the Thrift compiler
 * (0.9.3 will not work) and compile your thrift files with 'json' code generation and
 * include the resulting json files in the classpath location META-INF/armeria/thrift.
 * The classpath location can be changed by setting the armeria.thrift.json.dir system property.
 */
final class ThriftDocString {

    private static final Logger logger = LoggerFactory.getLogger(ThriftDocString.class);

    private static final String THRIFT_JSON_PATH;

    private static final TypeReference<HashMap<String, Object>> JSON_VALUE_TYPE =
            new TypeReference<HashMap<String, Object>>() {};

    private static final String FQCN_DELIM = ".";

    private static final String DELIM = "#";

    private static final Map<ClassLoader, Map<String, String>> cached = new ConcurrentHashMap<>();

    static {
        final String propertyName = "com.linecorp.armeria.thrift.jsonDir";
        String dir = System.getProperty(propertyName, "META-INF/armeria/thrift");
        if (dir.startsWith("/") || dir.startsWith("\\")) {
            dir = dir.substring(1);
        }
        if (dir.endsWith("/") || dir.endsWith("\\")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        logger.info("{}: {}", propertyName, dir);
        THRIFT_JSON_PATH = dir;
    }

    private ThriftDocString() {
    }

    /**
     * Extracts all DocStrings from all Thrift IDL JSON Resources.
     * @return a map with key is FQCN and value is document string.
     */
    static Map<String, String> getAllDocStrings(ClassLoader classLoader) {
        return cached.computeIfAbsent(classLoader, loader -> parseDocStrings(loader, getAllThriftJsons(loader)));
    }

    /**
     * Parses DocStrings from input Thrift IDL JSON Resources.
     * @return a map with key is FQCN and value is document string.
     */
    static Map<String, String> parseDocStrings(ClassLoader classLoader, Iterable<String> jsonPaths) {
        final ImmutableMap.Builder<String, String> docStrings = ImmutableMap.builder();
        for (String jsonPath : jsonPaths) {
            docStrings.putAll(getDocStringsFromJsonResource(classLoader, jsonPath));
        }
        return docStrings.build();
    }

    static Iterable<String> getAllThriftJsons(ClassLoader classLoader) {
        final Configuration configuration = new ConfigurationBuilder()
                .filterInputsBy(new FilterBuilder().includePackage(THRIFT_JSON_PATH))
                .setUrls(ClasspathHelper.forPackage(THRIFT_JSON_PATH))
                .addClassLoader(classLoader)
                .addScanners(new ResourcesScanner());
        if (configuration.getUrls() == null || configuration.getUrls().isEmpty()) {
            return Collections.emptyList();
        }
        return new Reflections(configuration).getResources(filename -> filename.endsWith(".json"));
    }

    /**
     * Gets the namespace key of names.
     * @param names name list.
     * @return merged key.
     */
    static String key(String... names) {
        return Stream.of(names).filter(s -> !Strings.isNullOrEmpty(s)).collect(Collectors.joining(DELIM));
    }

    @VisibleForTesting
    static Map<String, String> getDocStringsFromJsonResource(ClassLoader classLoader, String jsonResourcePath) {
        ImmutableMap.Builder<String, String> docStrings = ImmutableMap.builder();
        try (InputStream in = classLoader.getResourceAsStream(jsonResourcePath)) {
            if (in == null) {
                throw new IllegalStateException("not found: " + jsonResourcePath);
            }

            final Map<String, Object> json = new ObjectMapper().readValue(in, JSON_VALUE_TYPE);
            @SuppressWarnings("unchecked")
            final Map<String, Object> namespaces = (Map<String, Object>) json.getOrDefault("namespaces",
                                                                                           ImmutableMap.of());
            final String packageName = (String) namespaces.get("java");
            json.forEach((key, children) -> {
                if (children instanceof Collection) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> castChildren = (Collection<Object>) children;
                    castChildren.forEach(
                            grandChild -> traverseChildren(docStrings, packageName, FQCN_DELIM, grandChild));
                }
            });

            return docStrings.build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void traverseChildren(ImmutableMap.Builder<String, String> docStrings, String prefix,
                                         String delimiter, Object node) {
        if (node instanceof Map) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) node;
            final String name = (String) map.get("name");
            final String doc = (String) map.get("doc");
            String childPrefix;
            if (name != null) {
                childPrefix = MoreObjects.firstNonNull(prefix, "") + delimiter + name;
                if (doc != null) {
                    docStrings.put(childPrefix, doc.trim());
                }
            } else {
                childPrefix = prefix;
            }
            map.forEach((key, value) -> traverseChildren(docStrings, childPrefix, DELIM, value));
        } else if (node instanceof Iterable) {
            @SuppressWarnings("unchecked")
            final Iterable<Object> children = (Iterable<Object>) node;
            children.forEach(child -> traverseChildren(docStrings, prefix, DELIM, child));
        }
    }
}
