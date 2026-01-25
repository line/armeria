/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.server.docs;

import java.util.regex.Pattern;

/**
 * Utility patterns for parsing Javadoc-style documentation tags ({@code @param}, {@code @return},
 * {@code @throws}).
 * These patterns match tags at the start of a line (after optional whitespace and {@code *} for
 * Javadoc-style).
 */
public final class DocStringTagPatterns {

    /**
     * Matches {@code @param <type> <name> - <description>} or {@code @param <type> <name> <description>}.
     * Group 1: parameter name, Group 2: description (optional).
     */
    public static final Pattern PARAM =
            Pattern.compile("^[\\s*]*@param\\s+\\S+\\s+(\\S+)\\s*[-–]?\\s*(.*\\S)?\\s*$",
                            Pattern.MULTILINE);

    /**
     * Matches {@code @return <description>}.
     * Group 1: description.
     */
    public static final Pattern RETURN =
            Pattern.compile("^[\\s*]*@return\\s+(.*\\S)\\s*$", Pattern.MULTILINE);

    /**
     * Matches {@code @throws <type> - <description>} or {@code @throws <type> <description>}.
     * Group 1: exception type, Group 2: description (optional).
     */
    public static final Pattern THROWS =
            Pattern.compile("^[\\s*]*@throws\\s+(\\S+)\\s*[-–]?\\s*(.*\\S)?\\s*$",
                            Pattern.MULTILINE);

    private DocStringTagPatterns() {}
}
