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

package com.linecorp.armeria.server.servlet;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

final class UrlMapper<T> {
    private final boolean singlePattern;
    private final List<Element<T>> elementList = new ArrayList<>();

    UrlMapper(boolean singlePattern) {
        this.singlePattern = singlePattern;
    }

    void addMapping(String urlPattern, T object, String objectName) {
        requireNonNull(urlPattern, "urlPattern");
        requireNonNull(object, "object");
        requireNonNull(objectName, "objectName");
        if (elementList.stream()
                       .filter(x -> singlePattern && x.name.equals(objectName))
                       .findAny().orElse(null) != null) {
            throw new IllegalArgumentException("The [" + objectName + "] mapping exist!");
        }

        final Element element = elementList.stream()
                                           .filter(x -> x.pattern.equals(urlPattern))
                                           .findFirst().orElse(null);
        if (element != null) {
            element.name = objectName;
            element.object = object;
        } else {
            elementList.add(new Element<>(urlPattern, object, objectName));
        }
    }

    @Nullable
    Element<T> getMapping(String absoluteUri) {
        requireNonNull(absoluteUri, "absoluteUri");
        return elementList
                .stream()
                .filter(x -> absoluteUri.equals(x.pattern)) // Match exact path: /home
                .findAny()
                .orElse(
                        // Match contain *: /home/*.html
                        elementList
                                .stream()
                                .filter(s -> s.pattern.contains("*") &&
                                             Pattern.compile(s.pattern.replace(".", "\\.")
                                                                      .replace("*", ".*"))
                                                    .matcher(absoluteUri)
                                                    .find())
                                .findAny()
                                .orElse(null));
    }

    static class Element<T> {
        String pattern;
        T object;
        String name;
        String path;

        Element(String pattern, T object, String name) {
            requireNonNull(name, "objectName");
            requireNonNull(object, "object");
            this.pattern = pattern;
            this.object = object;
            this.name = name;
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
            path = joiner.toString();
        }

        T getObject() {
            return object;
        }
    }

    static String normalizePath(String path) {
        requireNonNull(path, "path");
        if (!path.isEmpty() && path.charAt(path.length() - 1) == '/') {
            path = path.substring(0, path.length() - 1);
        }
        return path.trim();
    }
}
