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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex based sanitizer.
 */
public class RegexBasedSanitizer extends AbstractSanitizerBase implements Function<Object, Object> {

    private final List<Pattern> patterns;

    /**
     * Constructor.
     * @param p Regex pattern.
     */
    RegexBasedSanitizer(List<Pattern> p) {
        this.patterns = p;
    }

    @Override
    public Object apply(Object input) {
        String rawData = (String)super.apply(input);
        for (Pattern pattern : patterns) {
            final Matcher m = pattern.matcher(rawData);
            if (m.find()) {
                rawData = m.replaceAll("");
            }
        }
        return rawData;
    }

    /**
     * Builder facilitates building regexsanitizer.
     */
    public static class RegexBasedSanitizerBuilder {

        private List<String> regexPatterns;

        /**
         * Constructor.
         */
        public RegexBasedSanitizerBuilder() {
            regexPatterns = new ArrayList<>();
        }

        /**
         * Set regex pattern.
         * @param p string regex pattern.
         * @return
         */
        public RegexBasedSanitizerBuilder pattern(String p) {
            this.regexPatterns.add(p);
            return this;
        }

        /**
         * Build RegexBasedSanitizer.
         * @return RegexBasedSanitizer.
         */
        public RegexBasedSanitizer build() {
            final List<Pattern> patterns = new ArrayList<>();
            if (!regexPatterns.isEmpty()) {
                regexPatterns.forEach(p -> patterns.add(Pattern.compile(p)));
            }
            return new RegexBasedSanitizer(patterns);
        }
    }
}
