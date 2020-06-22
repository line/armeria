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

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

final class ServletUrlMapper {

    private final Map<Pattern, DefaultServletRegistration> registrations = new HashMap<>();

    void addMapping(String urlPattern, DefaultServletRegistration registration) {
        // TODO Optimize router.
        final Pattern pattern;
        if (urlPattern.endsWith("/*")) {
            pattern = Pattern.compile('^' + urlPattern.substring(0, urlPattern.length() - 2) + ".*?");
        } else if (urlPattern.startsWith("*.")) {
            // extension mapping.
            if (urlPattern.length() == 2) {
                throw new IllegalArgumentException();
            }
            pattern = Pattern.compile(".*\\." + urlPattern.substring(2));
        } else {
            // exact match.
            final String normalizedPath = removeTrailingSlash(urlPattern);
            pattern = Pattern.compile('^' + normalizedPath + '$');
        }
        registrations.put(pattern, registration);
    }

    @Nullable
    DefaultServletRegistration getMapping(String path) {
        final String normalizedPath = removeTrailingSlash(path);
        return registrations.entrySet().stream()
                            .filter(entry -> entry.getKey().matcher(normalizedPath).matches())
                            .map(Entry::getValue).findFirst().orElse(null);
    }

    private static String removeTrailingSlash(String url) {
        if (!url.isEmpty() && url.charAt(url.length() - 1) == '/') {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
