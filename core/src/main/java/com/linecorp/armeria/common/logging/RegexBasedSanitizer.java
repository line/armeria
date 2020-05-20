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
package com.linecorp.armeria.common.logging;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex based sanitizer.
 */
public final class RegexBasedSanitizer implements Function<Object, String> {

    private final List<Pattern> patterns;

    /**
     * Constructor.
     * @param p Regex pattern.
     */
    RegexBasedSanitizer(List<Pattern> p) {
        this.patterns = p;
    }

    @Override
    public String apply(Object input) {
        String rawData = input.toString();
        for (Pattern pattern : patterns) {
            final Matcher m = pattern.matcher(rawData);
            if (m.find()) {
                rawData = m.replaceAll("");
            }
        }
        return rawData;
    }

    /**
     * Utility method to create RegexBasedSanitizer.
     * @param p Pattern.
     * @return
     */
    public static RegexBasedSanitizer of(Pattern...p) {
        return new RegexBasedSanitizer(Arrays.asList(p));
    }
}
