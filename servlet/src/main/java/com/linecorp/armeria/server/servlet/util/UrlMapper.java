/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.servlet.util;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import javax.annotation.Nullable;

/**
 * Url mapping
 * Mapping specification
 * In the web application deployment descriptor.
 */
public class UrlMapper<T> {
    @Nullable
    private String rootPath;
    private final boolean singlePattern;
    private final List<Element<T>> elementList = new ArrayList<>();
    private final StringUtil stringUtil = new StringUtil();

    /**
     * Creates a new instance.
     */
    public UrlMapper(boolean singlePattern) {
        this.singlePattern = singlePattern;
    }

    /**
     * Add mapping.
     * @param urlPattern  urlPattern.
     * @param object     object.
     * @param objectName objectName.
     */
    public void addMapping(String urlPattern, T object, String objectName) throws IllegalArgumentException {
        requireNonNull(urlPattern, "urlPattern");
        requireNonNull(object, "object");
        requireNonNull(objectName, "objectName");
        if (elementList.stream()
                       .filter(x -> singlePattern && x.objectName.equals(objectName))
                       .findAny().orElse(null) != null) {
            throw new IllegalArgumentException("The [" + objectName + "] mapping exist!");
        }

        final Element element = elementList.stream()
                                           .filter(x -> x.originalPattern.equals(urlPattern))
                                           .findFirst().orElse(null);
        if (element != null) {
            element.objectName = objectName;
            element.object = object;
        } else {
            elementList.add(new Element<>(rootPath, urlPattern, object, objectName));
        }
    }

    /**
     * Gets a servlet path.
     * @param absoluteUri An absolute path.
     * @return servlet path.
     */
    public String getServletPath(String absoluteUri) {
        requireNonNull(absoluteUri, "absoluteUri");
        return elementList.stream()
                          .filter(x -> stringUtil.match(x.pattern, absoluteUri, "*"))
                          .map(x -> x.servletPath)
                          .findAny()
                          .orElse(absoluteUri);
    }

    /**
     * Gets a mapping object.
     * @param absoluteUri An absolute path.
     * @return T object.
     */
    @Nullable
    public Element<T> getMappingObjectByUri(String absoluteUri) {
        requireNonNull(absoluteUri, "absoluteUri");
        return elementList.stream()
                          .filter(x -> stringUtil.match(x.pattern, absoluteUri, "*"))
                          .findAny()
                          .orElse(
                                  elementList.stream()
                                             .filter(s -> !"default".equals(s.objectName) &&
                                                          ('/' == s.pattern.charAt(0) ||
                                                           '*' == s.pattern.charAt(0) ||
                                                           "/*".equals(s.pattern) ||
                                                           "/**".equals(s.pattern)))
                                             .findAny()
                                             .orElse(
                                                     elementList.stream()
                                                                .filter(a -> "default".equals(a.objectName))
                                                                .findAny()
                                                                .orElse(null)
                                             )
                          );
    }

    /**
     * Add multiple mapping objects.
     * @param list add in list.
     * @param absoluteUri An absolute path.
     */
    public void addMappingObjectsByUri(String absoluteUri, List<T> list) {
        requireNonNull(absoluteUri, "absoluteUri");
        requireNonNull(list, "list");
        elementList.stream()
                   .filter(x -> "/*".equals(x.pattern) ||
                                (absoluteUri.length() == 1 &&
                                 '/' == absoluteUri.charAt(0) && '/' == x.pattern.charAt(0)) ||
                                '*' == x.pattern.charAt(0) || "/**".equals(x.pattern) ||
                                stringUtil.match(x.pattern, absoluteUri, "*"))
                   .forEach(x -> list.add(x.object));
    }

    /**
     * Class element.
     */
    public static class Element<T> {
        String pattern;
        String originalPattern;
        T object;
        String objectName;
        String servletPath;
        String rootPath;

        Element(@Nullable String rootPath, String originalPattern, T object, String objectName) {
            requireNonNull(originalPattern, "originalPattern");
            requireNonNull(objectName, "objectName");
            requireNonNull(object, "object");
            if (rootPath != null) {
                pattern = rootPath.concat(originalPattern);
            } else {
                pattern = originalPattern;
            }
            this.rootPath = rootPath;
            this.originalPattern = originalPattern;
            this.object = object;
            this.objectName = objectName;
            final StringJoiner joiner = new StringJoiner("/");
            final String[] pattens = pattern.split("/");
            for (int i = 0; i < pattens.length; i++) {
                final String path = pattens[i];
                if (path.contains("*")) {
                    if (i == pattens.length - 1) {
                        continue;
                    }
                }
                joiner.add(path);
            }
            servletPath = joiner.toString();
        }

        /**
         * Get object.
         */
        public T getObject() {
            return object;
        }
    }
}
