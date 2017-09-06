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

package com.linecorp.armeria.server.thrift;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.server.docs.DocStringExtractor;

/**
 * {@link ThriftDocStringExtractor} is a DocString extractor for Thrift IDL JSON.
 *
 * <p>To include docstrings in {@link com.linecorp.armeria.server.docs.DocService} pages, use a recent
 * development version of the Thrift compiler (0.9.3 will not work) and compile your thrift files with 'json'
 * code generation and include the resulting json files in the classpath location
 * {@code META-INF/armeria/thrift}. The classpath location can be changed by setting the
 * {@code com.linecorp.armeria.thrift.jsonDir} system property.
 */
final class ThriftDocStringExtractor extends DocStringExtractor {

    private static final TypeReference<HashMap<String, Object>> JSON_VALUE_TYPE =
            new TypeReference<HashMap<String, Object>>() {};

    private static final String FQCN_DELIM = ".";

    private static final String DELIM = "/";

    ThriftDocStringExtractor() {
        super("META-INF/armeria/thrift", "com.linecorp.armeria.thrift.jsonDir");
    }

    @Override
    protected boolean acceptFile(String filename) {
        return filename.endsWith(".json");
    }

    /**
     * Gets the namespace key of names.
     * @param names name list.
     * @return merged key.
     */
    static String key(String... names) {
        return Stream.of(names).filter(s -> !Strings.isNullOrEmpty(s)).collect(Collectors.joining(DELIM));
    }

    @Override
    protected Map<String, String> getDocStringsFromFiles(Map<String, byte[]> files) {
        ImmutableMap.Builder<String, String> docStrings = ImmutableMap.builder();
        for (byte[] file : files.values()) {
            try {
                final Map<String, Object> json = new ObjectMapper().readValue(file, JSON_VALUE_TYPE);
                @SuppressWarnings("unchecked")
                final Map<String, Object> namespaces =
                        (Map<String, Object>) json.getOrDefault("namespaces", ImmutableMap.of());
                final String packageName = (String) namespaces.get("java");
                json.forEach((key, children) -> {
                    if (children instanceof Collection) {
                        @SuppressWarnings("unchecked")
                        Collection<Object> castChildren = (Collection<Object>) children;
                        castChildren.forEach(
                                grandChild -> traverseChildren(docStrings, packageName, FQCN_DELIM,
                                                               grandChild));
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return docStrings.build();
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
