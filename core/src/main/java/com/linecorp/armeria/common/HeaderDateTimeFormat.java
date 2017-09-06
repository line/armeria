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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * Parses and formats 3 date-and-time representations.
 *
 * <p><ul>
 * <li>Sun, 06 Nov 1994 08:49:37 GMT: standard specification, the only one with valid generation</li>
 * <li>Sun, 06 Nov 1994 08:49:37 GMT: obsolete specification</li>
 * <li>Sun Nov 6 08:49:37 1994: obsolete specification</li>
 * </ul>
 */
final class HeaderDateTimeFormat {

    private static final ZoneId GMT = ZoneId.of("GMT");

    /**
     * Standard date format.
     *
     * <p><pre>
     * Sun, 06 Nov 1994 08:49:37 GMT -> E, d MMM yyyy HH:mm:ss z
     * </pre>
     */
    private static final DateTimeFormatter format1 = newFormat("E, dd MMM yyyy HH:mm:ss z");

    /**
     * First obsolete format.
     *
     * <p><pre>
     * Sunday, 06-Nov-94 08:49:37 GMT -> E, d-MMM-y HH:mm:ss z
     * </pre>
     */
    private static final DateTimeFormatter format2 = newFormat("E, dd-MMM-yy HH:mm:ss z");

    /**
     * A variant of the first obsolete format, which handles 20th century years such as 1994.
     *
     * @see <a href="https://stackoverflow.com/a/29496149">http://stackoverflow.com/a/29496149</a>
     */
    private static final DateTimeFormatter format2a =
            new DateTimeFormatterBuilder().parseLenient()
                                          .parseCaseInsensitive()
                                          .appendPattern("E, dd-MMM-")
                                          .appendValueReduced(
                                                  ChronoField.YEAR, 2, 2, Year.now().getValue() - 80)
                                          .appendPattern(" HH:mm:ss z")
                                          .toFormatter(Locale.ENGLISH).withZone(GMT);

    /**
     * Second obsolete format.
     *
     * <p><pre>
     * Sun Nov 6 08:49:37 1994 -> EEE, MMM d HH:mm:ss yyyy
     * </pre>
     */
    private static final DateTimeFormatter format3 = newFormat("E MMM d HH:mm:ss yyyy");

    private static DateTimeFormatter newFormat(String pattern) {
        return new DateTimeFormatterBuilder().parseLenient()
                                             .parseCaseInsensitive()
                                             .appendPattern(pattern)
                                             .toFormatter(Locale.ENGLISH).withZone(GMT);
    }

    static Instant parse(String text) {
        requireNonNull(text);
        try {
            return parse(format1, text);
        } catch (Exception e1) {
            // Try the second preferred format.
            try {
                return parse(format2, text);
            } catch (Exception e2) {
                // Try the variant of the second preferred format.
                try {
                    return parse(format2a, text);
                } catch (Exception e2a) {
                    // Try the third preferred format.
                    try {
                        return parse(format3, text);
                    } catch (Exception e3) {
                        // None worked.
                        throw new IllegalArgumentException("not a date: " + text);
                    }
                }
            }
        }
    }

    private static Instant parse(DateTimeFormatter formatter, String text) {
        return formatter.parse(text, Instant::from);
    }

    static String format(long timeMillis) {
        return format1.format(Instant.ofEpochMilli(timeMillis));
    }

    private HeaderDateTimeFormat() {}
}
