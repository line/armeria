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

package com.linecorp.armeria.internal.server.thrift;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocStringExtractor;

/**
 * {@link ThriftDocStringExtractor} is a DocString extractor for Thrift IDL JSON.
 *
 * <p>To include docstrings in {@link DocService} pages, use the Thrift compiler version 0.10.0 or above
 * (0.9.3 will not work) and compile your thrift files with 'json' code generation and include the resulting
 * json files in the classpath location {@code META-INF/armeria/thrift}. The classpath location can be changed
 * by setting the {@code com.linecorp.armeria.thrift.jsonDir} system property.
 */
final class ThriftDocStringExtractor extends DocStringExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ThriftDocStringExtractor.class);
    private static final TypeReference<HashMap<String, Object>> JSON_VALUE_TYPE =
            new TypeReference<HashMap<String, Object>>() {};

    private static final String FQCN_DELIM = ".";

    private static final String DELIM = "/";

    /**
     * A {@link Pattern} to match {@code @return} tag in docstrings.
     * The tag must appear at the start of a line (after optional whitespace and {@code *} for Javadoc-style).
     */
    private static final Pattern RETURN_PATTERN =
            Pattern.compile("^[\\s*]*@return\\s+(.+)$", Pattern.MULTILINE);

    /**
     * A {@link Pattern} to match {@code @throws} tag in docstrings.
     * Matches: {@code @throws <type> <description>} or {@code @throws <type> - <description>}.
     * The dash separator is optional.
     * The tag must appear at the start of a line (after optional whitespace and {@code *} for Javadoc-style).
     */
    private static final Pattern THROWS_PATTERN =
            Pattern.compile("^[\\s*]*@throws\\s+(\\S+)\\s*[-–]?\\s*(.*)$", Pattern.MULTILINE);

    ThriftDocStringExtractor() {
        super("META-INF/armeria/thrift", "com.linecorp.armeria.thrift.jsonDir");
    }

    @Override
    protected boolean acceptFile(String filename) {
        return filename.endsWith(".json");
    }

    @Override
    protected Map<String, String> getDocStringsFromFiles(Map<String, byte[]> files) {
        final ImmutableMap.Builder<String, String> docStrings = ImmutableMap.builder();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            try {
                final Map<String, Object> json =
                        new ObjectMapper().readValue(entry.getValue(), JSON_VALUE_TYPE);
                @SuppressWarnings("unchecked")
                final Map<String, Object> namespaces =
                        (Map<String, Object>) json.getOrDefault("namespaces", ImmutableMap.of());
                final String packageName = (String) namespaces.get("java");
                if (packageName == null) {
                    logger.info("Skipping Thrift DocString generation for file: {}", entry.getKey());
                    continue;
                }
                json.forEach((key, children) -> {
                    if (children instanceof Collection) {
                        @SuppressWarnings("unchecked")
                        final Collection<Object> castChildren = (Collection<Object>) children;
                        castChildren.forEach(
                                grandChild -> traverseChildren(docStrings, packageName, FQCN_DELIM,
                                                               grandChild, packageName));
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return docStrings.build();
    }

    private static void traverseChildren(ImmutableMap.Builder<String, String> docStrings, String prefix,
                                         String delimiter, Object node, String packageName) {
        if (node instanceof Map) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) node;
            final String name = (String) map.get("name");
            final String doc = (String) map.get("doc");
            final String childPrefix;
            if (name != null) {
                childPrefix = prefix + delimiter + name;
                if (doc != null) {
                    final String trimmedDoc = doc.trim();
                    docStrings.put(childPrefix, trimmedDoc);

                    // Check if this is a function (has returnTypeId field)
                    if (map.containsKey("returnTypeId")) {
                        // Parse @return tag from docstring
                        parseReturnDocString(docStrings, childPrefix, trimmedDoc);

                        // Parse @throws tags from docstring
                        parseThrowsDocStrings(docStrings, childPrefix, trimmedDoc, packageName,
                                              getExceptionNames(map));
                    }
                }
            } else {
                childPrefix = prefix;
            }
            map.forEach((key, value) -> traverseChildren(docStrings, childPrefix, DELIM, value, packageName));
        } else if (node instanceof Iterable) {
            @SuppressWarnings("unchecked")
            final Iterable<Object> children = (Iterable<Object>) node;
            children.forEach(child -> traverseChildren(docStrings, prefix, DELIM, child, packageName));
        }
    }

    /**
     * Parses the @return tag from a docstring and adds it to the docStrings map.
     */
    private static void parseReturnDocString(ImmutableMap.Builder<String, String> docStrings,
                                             String methodKey, String doc) {
        final Matcher matcher = RETURN_PATTERN.matcher(doc);
        if (matcher.find()) {
            final String returnDescription = matcher.group(1).trim();
            if (!returnDescription.isEmpty()) {
                docStrings.put(methodKey + ":return", returnDescription);
            }
        }
    }

    /**
     * Parses @throws tags from a docstring and adds them to the docStrings map.
     */
    private static void parseThrowsDocStrings(ImmutableMap.Builder<String, String> docStrings,
                                              String methodKey, String doc, String packageName,
                                              List<String> exceptionNames) {
        final Matcher matcher = THROWS_PATTERN.matcher(doc);
        while (matcher.find()) {
            final String exceptionType = matcher.group(1).trim();
            final String throwsDescription = matcher.group(2).trim();
            if (!throwsDescription.isEmpty()) {
                // Find the fully qualified exception name
                final String fqcn = findExceptionFqcn(exceptionType, packageName, exceptionNames);
                if (fqcn != null) {
                    docStrings.put(methodKey + ":throws/" + fqcn, throwsDescription);
                }
            }
        }
    }

    /**
     * Finds the fully qualified class name for an exception type.
     */
    @Nullable
    private static String findExceptionFqcn(String exceptionType, String packageName,
                                            List<String> exceptionNames) {
        // If the exception type is already fully qualified, use it directly
        if (exceptionType.contains(".")) {
            return exceptionType;
        }

        // Check if the exception name matches any declared exception
        for (String exceptionName : exceptionNames) {
            if (exceptionName.equals(exceptionType)) {
                // The exception name in the JSON is just the simple name, so add the package
                return packageName + "." + exceptionType;
            }
            if (exceptionName.endsWith("." + exceptionType)) {
                return exceptionName;
            }
        }

        // Default to package name + exception type
        return packageName + "." + exceptionType;
    }

    /**
     * Extracts exception class names from a function's exceptions field.
     */
    private static List<String> getExceptionNames(Map<String, Object> functionMap) {
        final Object exceptions = functionMap.get("exceptions");
        if (!(exceptions instanceof List)) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> names = ImmutableList.builder();
        @SuppressWarnings("unchecked")
        final List<Object> exceptionList = (List<Object>) exceptions;
        for (Object exception : exceptionList) {
            if (exception instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> exceptionMap = (Map<String, Object>) exception;
                final Object type = exceptionMap.get("type");
                if (type instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> typeMap = (Map<String, Object>) type;
                    final String className = (String) typeMap.get("class");
                    if (className != null) {
                        names.add(className);
                    }
                }
            }
        }
        return names.build();
    }
}
