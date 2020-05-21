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
package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

/**
 * Regex based sanitizer.
 */
public final class RegexBasedSanitizer implements Function<Object, String> {

    /**
     * Returns a new instance created from the specified {@link Pattern}s.
     */
    public static RegexBasedSanitizer of(Pattern... patterns) {
        requireNonNull(patterns, "patterns");
        return new RegexBasedSanitizer(ImmutableList.copyOf(patterns));
    }

    /**
     * Returns a new instance created from the specified {@link Pattern}s.
     */
    public static RegexBasedSanitizer of(Iterable<Pattern> patterns) {
        requireNonNull(patterns, "patterns");
        return new RegexBasedSanitizer(ImmutableList.copyOf(patterns));
    }

    private final List<Pattern> patterns;

    /**
     * Creates a new instance.
     * @param patterns {@link Pattern}.
     */
    RegexBasedSanitizer(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    @Nullable
    @Override
    public String apply(@Nullable Object input) {
        if (input == null) {
            return null;
        }

        String rawData = input.toString();
        for (Pattern pattern : patterns) {
            final Matcher m = pattern.matcher(rawData);
            if (m.find()) {
                rawData = m.replaceAll("");
            }
        }
        return rawData;
    }
}
