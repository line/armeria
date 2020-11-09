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
package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceTypeUtil.stringToType;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class AnnotatedServiceTypeUtilTest {

    @Test
    void supportedStringToType() {
        final UUID uuid = UUID.randomUUID();
        assertEquals(uuid, stringToType(uuid.toString(), UUID.class));
    }

    @Test
    void stringOf() {
        final String testString = UUID.randomUUID().toString();
        assertEquals(testString, stringToType(testString, StringOf.class).str);
    }

    @Test
    void stringValueOf() {
        final String testString = UUID.randomUUID().toString();
        assertEquals(testString, stringToType(testString, StringValueOf.class).str);
    }

    @Test
    void stringFromString() {
        final String testString = UUID.randomUUID().toString();
        assertEquals(testString, stringToType(testString, StringFromString.class).str);
    }

    @Test
    void stringConstructor() {
        final String testString = UUID.randomUUID().toString();
        assertEquals(testString, stringToType(testString, StringConstructor.class).str);
    }

    @Test
    void stringConstructorSkipValueOfWithWrongReturnType() {
        final String testString = UUID.randomUUID().toString();
        final StringConstructorSkipValueOfWithWrongReturnType value =
            stringToType(testString, StringConstructorSkipValueOfWithWrongReturnType.class);
        assertEquals(testString, value.str);
    }

    @Test
    void enumShouldUseOfIfAvailable() {
        final EnumWithOfCreator value =
                stringToType("foo", EnumWithOfCreator.class);
        assertEquals(EnumWithOfCreator.FOO, value);
    }

    public static final class StringOf {
        private final String str;

        private StringOf(final String str) {
            this.str = str;
        }

        public static StringOf of(String str) {
            return new StringOf(str);
        }

        public static StringOf valueOf(String str) {
            throw new AssertionError("Not supposed to run this lower prio method");
        }

        public static StringOf fromString(String str) {
            throw new AssertionError("Not supposed to run this lower prio method");
        }
    }

    public static final class StringValueOf {
        private final String str;

        private StringValueOf(final String str) {
            this.str = str;
        }

        private static StringValueOf of(String str) {
            throw new AssertionError("Not supposed to run this PRIVATE method");
        }

        public static StringValueOf valueOf(String str) {
            return new StringValueOf(str);
        }

        public static StringValueOf fromString(String str) {
            throw new AssertionError("Not supposed to run this lower prio method");
        }
    }

    public static final class StringFromString {
        private final String str;

        private StringFromString(final String str) {
            this.str = str;
        }

        private static StringFromString of(String str) {
            throw new AssertionError("Not supposed to run this PRIVATE method");
        }

        private static StringFromString valueOf(String str) {
            throw new AssertionError("Not supposed to run this PRIVATE method");
        }

        public static StringFromString fromString(String str) {
            return new StringFromString(str);
        }
    }

    public static final class StringConstructor {
        private final String str;

        public StringConstructor(final String str) {
            this.str = str;
        }

        private static StringConstructor of(String str) {
            throw new AssertionError("Not supposed to run this PRIVATE method");
        }

        private static StringConstructor valueOf(String str) {
            throw new AssertionError("Not supposed to run this PRIVATE method");
        }

        private static StringConstructor fromString(String str) {
            throw new AssertionError("Not supposed to run this PRIVATE method");
        }
    }

    public static final class StringConstructorSkipValueOfWithWrongReturnType {
        private final String str;

        public StringConstructorSkipValueOfWithWrongReturnType(final String str) {
            this.str = str;
        }

        public static StringConstructor valueOf(String str) {
            return new StringConstructor(str);
        }
    }

    public enum EnumWithOfCreator {
        FOO,
        BAR;

        public static EnumWithOfCreator of(String str) {
            return valueOf(str.toUpperCase(Locale.US));
        }
    }
}
