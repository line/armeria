/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.linecorp.armeria.common.http.HttpRequest;

/**
 * Utility class for extracting path params.
 */
public class PathParamExtractor {
    @SuppressWarnings("unchecked")
    private static final Map<String, String> EMPTY_MAP = Collections.EMPTY_MAP;
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");
    private final Pattern extractPattern;
    private final List<String> pathParams;
    private final int pathParamSize;

    /**
     * Constructs an path parameter extractor with pattern.
     * @param pattern String that contains path params, like /users/{name}
     * @throws IllegalArgumentException if the pattern is invalid.
     */
    public PathParamExtractor(String pattern) {
        requireNonNull(pattern, "pattern must not be null");

        final Matcher matcher = PATH_PARAM_PATTERN.matcher(pattern);
        pathParams = new ArrayList<>();

        while (matcher.find()) {
            pathParams.add(matcher.group(1));
        }

        pathParamSize = pathParams.size();

        final String expression = matcher.replaceAll("([^/?]+)") + "(\\?.*)*";
        try {
            extractPattern = Pattern.compile(expression);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid pattern - " + pattern);
        }
    }

    /**
     * extract path parameters from {@link HttpRequest}.
     * @param req {@link HttpRequest} for finding matched path parameters.
     * @return {@link Map} that contains path parameter values or empty map if request does not match.
     */
    public Map<String, String> extract(HttpRequest req) {
        final String path = req.path();

        final Matcher matcher = extractPattern.matcher(path);

        if (!matcher.matches()) {
            return EMPTY_MAP;
        }

        final Map<String, String> values = new HashMap<>();

        if (matcher.groupCount() < pathParamSize) {
            return EMPTY_MAP;
        }

        for (int i = 0; i < pathParamSize; i++) {
            values.put(pathParams.get(i), matcher.group(i + 1));
        }

        return Collections.unmodifiableMap(values);
    }
}
