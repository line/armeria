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
/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common;

import static com.google.common.base.Charsets.UTF_16;
import static com.google.common.base.Charsets.UTF_8;
import static com.linecorp.armeria.common.MediaType.ANY_APPLICATION_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_AUDIO_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_IMAGE_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_TEXT_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_VIDEO_TYPE;
import static com.linecorp.armeria.common.MediaType.GRAPHQL;
import static com.linecorp.armeria.common.MediaType.HTML_UTF_8;
import static com.linecorp.armeria.common.MediaType.JPEG;
import static com.linecorp.armeria.common.MediaType.JSON;
import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.EqualsTester;
import com.google.common.testing.NullPointerTester;

/**
 * Tests for {@link MediaType}.
 *
 * @author Gregory Kick
 */
public class MediaTypeTest {

    // Forked from Guava 27.1 at 7da42d206b81c8fe184f73a9314fd8ffcf565560

    @Test // reflection
    public void testParse_useConstants() throws Exception {
        for (MediaType constant : getConstants()) {
            assertSame(constant, MediaType.parse(constant.toString()));
        }
    }

    @Test // reflection
    public void testCreate_useConstants() throws Exception {
        for (MediaType constant : getConstants()) {
            assertSame(
                    constant,
                    MediaType.create(constant.type(), constant.subtype())
                             .withParameters(constant.parameters()));
        }
    }

    @Test // reflection
    public void testConstants_charset() throws Exception {
        for (Field field : getConstantFields(MediaType.class)) {
            final Charset charset = ((MediaType) field.get(null)).charset();
            if (field.getName().endsWith("_UTF_8")) {
                assertThat(charset).isEqualTo(UTF_8);
            } else {
                assertThat(charset).isNull();
            }
        }
    }

    @Test // reflection
    public void testConstants_areUnique() {
        assertThat(getConstants()).doesNotHaveDuplicates();
    }

    // reflection
    @SuppressWarnings("Guava")
    static <T, R> FluentIterable<Field> getConstantFields(Class<T> clazz, Class<R>... filterClazz) {
        return FluentIterable.from(asList(clazz.getDeclaredFields())).filter(input -> {
            final int modifiers = input.getModifiers();
            return isPublic(modifiers) &&
                   isStatic(modifiers) &&
                   isFinal(modifiers) &&
                   filterClazz.length == 1 ?
                   filterClazz[0].equals(input.getType()) : clazz.equals(input.getType());
        });
    }

    // reflection
    @SuppressWarnings("Guava")
    private static FluentIterable<MediaType> getConstants() {
        return getConstantFields(MediaType.class).transform(input -> {
            try {
                return (MediaType) input.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testCreate_invalidType() {
        try {
            MediaType.create("te><t", "plaintext");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testCreate_invalidSubtype() {
        try {
            MediaType.create("text", "pl@intext");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testCreate_wildcardTypeDeclaredSubtype() {
        try {
            MediaType.create("*", "text");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testCreateApplicationType() {
        final MediaType newType = MediaType.createApplicationType("yams");
        assertEquals("application", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateAudioType() {
        final MediaType newType = MediaType.createAudioType("yams");
        assertEquals("audio", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateImageType() {
        final MediaType newType = MediaType.createImageType("yams");
        assertEquals("image", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateTextType() {
        final MediaType newType = MediaType.createTextType("yams");
        assertEquals("text", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateVideoType() {
        final MediaType newType = MediaType.createVideoType("yams");
        assertEquals("video", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testGetType() {
        assertEquals("text", MediaType.parse("text/plain").type());
        assertEquals("application", MediaType.parse("application/atom+xml; charset=utf-8").type());
    }

    @Test
    public void testGetSubtype() {
        assertEquals("plain", MediaType.parse("text/plain").subtype());
        assertEquals("atom+xml", MediaType.parse("application/atom+xml; charset=utf-8").subtype());
    }

    private static final Map<String, Collection<String>> PARAMETERS =
            ImmutableListMultimap.of("a", "1", "a", "2", "b", "3").asMap();

    @Test
    public void testGetParameters() {
        assertEquals(ImmutableMap.of(), MediaType.parse("text/plain").parameters());
        assertEquals(
                ImmutableMap.of("charset", ImmutableList.of("utf-8")),
                MediaType.parse("application/atom+xml; charset=utf-8").parameters());
        assertEquals(PARAMETERS, MediaType.parse("application/atom+xml; a=1; a=2; b=3").parameters());
    }

    @Test
    public void testWithoutParameters() {
        assertSame(MediaType.parse("image/gif"), MediaType.parse("image/gif").withoutParameters());
        assertEquals(
                MediaType.parse("image/gif"), MediaType.parse("image/gif; foo=bar").withoutParameters());
    }

    @Test
    public void testWithParameters() {
        assertEquals(
                MediaType.parse("text/plain; a=1; a=2; b=3"),
                MediaType.parse("text/plain").withParameters(PARAMETERS));
        assertEquals(
                MediaType.parse("text/plain; a=1; a=2; b=3"),
                MediaType.parse("text/plain; a=1; a=2; b=3").withParameters(PARAMETERS));
    }

    @Test
    public void testWithParameters_invalidAttribute() {
        final MediaType mediaType = MediaType.parse("text/plain");
        final ImmutableListMultimap<String, String> parameters =
                ImmutableListMultimap.of("a", "1", "@", "2", "b", "3");
        try {
            mediaType.withParameters(parameters.asMap());
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testWithParameter() {
        assertEquals(
                MediaType.parse("text/plain; a=1"), MediaType.parse("text/plain").withParameter("a", "1"));
        assertEquals(
                MediaType.parse("text/plain; a=1"),
                MediaType.parse("text/plain; a=1; a=2").withParameter("a", "1"));
        assertEquals(
                MediaType.parse("text/plain; a=3"),
                MediaType.parse("text/plain; a=1; a=2").withParameter("a", "3"));
        assertEquals(
                MediaType.parse("text/plain; a=1; a=2; b=3"),
                MediaType.parse("text/plain; a=1; a=2").withParameter("b", "3"));
    }

    @Test
    public void testWithParameter_invalidAttribute() {
        final MediaType mediaType = MediaType.parse("text/plain");
        try {
            mediaType.withParameter("@", "2");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testWithParametersIterable() {
        assertEquals(
                MediaType.parse("text/plain"),
                MediaType.parse("text/plain; a=1; a=2").withParameters("a", ImmutableSet.of()));
        assertEquals(
                MediaType.parse("text/plain; a=1"),
                MediaType.parse("text/plain").withParameters("a", ImmutableSet.of("1")));
        assertEquals(
                MediaType.parse("text/plain; a=1"),
                MediaType.parse("text/plain; a=1; a=2").withParameters("a", ImmutableSet.of("1")));
        assertEquals(
                MediaType.parse("text/plain; a=1; a=3"),
                MediaType.parse("text/plain; a=1; a=2").withParameters("a", ImmutableSet.of("1", "3")));
        assertEquals(
                MediaType.parse("text/plain; a=1; a=2; b=3; b=4"),
                MediaType.parse("text/plain; a=1; a=2").withParameters("b", ImmutableSet.of("3", "4")));
    }

    @Test
    public void testWithParametersIterable_invalidAttribute() {
        final MediaType mediaType = MediaType.parse("text/plain");
        try {
            mediaType.withParameters("@", ImmutableSet.of("2"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testWithParametersIterable_nullValue() {
        final MediaType mediaType = MediaType.parse("text/plain");
        try {
            mediaType.withParameters("a", Collections.singletonList(null));
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testWithCharset() {
        assertEquals(
                MediaType.parse("text/plain; charset=utf-8"),
                MediaType.parse("text/plain").withCharset(UTF_8));
        assertEquals(
                MediaType.parse("text/plain; charset=utf-8"),
                MediaType.parse("text/plain; charset=utf-16").withCharset(UTF_8));
    }

    @Test
    public void testHasWildcard() {
        assertFalse(PLAIN_TEXT_UTF_8.hasWildcard());
        assertFalse(JPEG.hasWildcard());
        assertTrue(ANY_TYPE.hasWildcard());
        assertTrue(ANY_APPLICATION_TYPE.hasWildcard());
        assertTrue(ANY_AUDIO_TYPE.hasWildcard());
        assertTrue(ANY_IMAGE_TYPE.hasWildcard());
        assertTrue(ANY_TEXT_TYPE.hasWildcard());
        assertTrue(ANY_VIDEO_TYPE.hasWildcard());
    }

    @Test
    public void testIs() {
        assertTrue(PLAIN_TEXT_UTF_8.is(ANY_TYPE));
        assertTrue(JPEG.is(ANY_TYPE));
        assertTrue(ANY_TEXT_TYPE.is(ANY_TYPE));
        assertTrue(PLAIN_TEXT_UTF_8.is(ANY_TEXT_TYPE));
        assertTrue(PLAIN_TEXT_UTF_8.withoutParameters().is(ANY_TEXT_TYPE));
        assertFalse(JPEG.is(ANY_TEXT_TYPE));
        assertTrue(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8));
        assertTrue(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8.withoutParameters()));
        assertFalse(PLAIN_TEXT_UTF_8.withoutParameters().is(PLAIN_TEXT_UTF_8));
        assertFalse(PLAIN_TEXT_UTF_8.is(HTML_UTF_8));
        assertFalse(PLAIN_TEXT_UTF_8.withParameter("charset", "UTF-16").is(PLAIN_TEXT_UTF_8));
        assertFalse(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8.withParameter("charset", "UTF-16")));
    }

    @Test
    public void testIsJson() {
        assertTrue(JSON.isJson());
        assertTrue(JSON_UTF_8.isJson());
        assertTrue(MediaType.parse("application/graphql+json").isJson());
        assertFalse(PLAIN_TEXT_UTF_8.isJson());
        assertFalse(GRAPHQL.isJson());
    }

    @Test
    public void testBelongsTo() {
        // For quality factor, "belongsTo" has a different behavior to "is".
        assertThat(PLAIN_TEXT_UTF_8.is(ANY_TYPE.withParameter("q", "0.9"))).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.belongsTo(ANY_TYPE.withParameter("q", "0.9"))).isTrue();

        // For the other parameters, "belongsTo" has the same behavior as "is".
        assertThat(PLAIN_TEXT_UTF_8.is(ANY_TYPE.withParameter("charset", "UTF-16"))).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.belongsTo(ANY_TYPE.withParameter("charset", "UTF-16"))).isFalse();
    }

    @Test
    public void testParse_empty() {
        try {
            MediaType.parse("");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testParse_badInput() {
        try {
            MediaType.parse("/");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("te<t/plain");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/pl@in");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain;");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; ");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=@");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=\"@");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=1;");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=1; ");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=1; b");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=1; b=");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            MediaType.parse("text/plain; a=\u2025");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testGetCharset() {
        assertThat(MediaType.parse("text/plain").charset()).isNull();
        assertThat(MediaType.parse("text/plain; charset=utf-8").charset()).isEqualTo(UTF_8);
    }

    @Test // Non-UTF-8 Charset
    public void testGetCharset_utf16() {
        assertThat(MediaType.parse("text/plain; charset=utf-16").charset()).isEqualTo(UTF_16);
    }

    @Test
    public void testGetCharset_tooMany() {
        final MediaType mediaType = MediaType.parse("text/plain; charset=utf-8; charset=utf-16");
        try {
            mediaType.charset();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testGetCharset_illegalCharset() {
        final MediaType mediaType = MediaType.parse("text/plain; charset=\"!@#$%^&*()\"");
        try {
            mediaType.charset();
            fail();
        } catch (IllegalCharsetNameException expected) {
        }
    }

    @Test
    public void testGetCharset_unsupportedCharset() {
        final MediaType mediaType = MediaType.parse("text/plain; charset=utf-wtf");
        try {
            mediaType.charset();
            fail();
        } catch (UnsupportedCharsetException expected) {
        }
    }

    @Test
    public void testEquals() {
        new EqualsTester()
                .addEqualityGroup(
                        MediaType.create("text", "plain"),
                        MediaType.create("TEXT", "PLAIN"),
                        MediaType.parse("text/plain"),
                        MediaType.parse("TEXT/PLAIN"),
                        MediaType.create("text", "plain").withParameter("a", "1").withoutParameters())
                .addEqualityGroup(
                        MediaType.create("text", "plain").withCharset(UTF_8),
                        MediaType.create("text", "plain").withParameter("CHARSET", "UTF-8"),
                        MediaType.create("text", "plain")
                                 .withParameters(ImmutableMultimap.of("charset", "utf-8").asMap()),
                        MediaType.parse("text/plain;charset=utf-8"),
                        MediaType.parse("text/plain; charset=utf-8"),
                        MediaType.parse("text/plain;  charset=utf-8"),
                        MediaType.parse("text/plain; \tcharset=utf-8"),
                        MediaType.parse("text/plain; \r\n\tcharset=utf-8"),
                        MediaType.parse("text/plain; CHARSET=utf-8"),
                        MediaType.parse("text/plain; charset=\"utf-8\""),
                        MediaType.parse("text/plain; charset=\"\\u\\tf-\\8\""),
                        MediaType.parse("text/plain; charset=UTF-8"),
                        MediaType.parse("text/plain ; charset=utf-8"))
                .addEqualityGroup(MediaType.parse("text/plain; charset=utf-8; charset=utf-8"))
                .addEqualityGroup(
                        MediaType.create("text", "plain").withParameter("a", "value"),
                        MediaType.create("text", "plain").withParameter("A", "value"))
                .addEqualityGroup(
                        MediaType.create("text", "plain").withParameter("a", "VALUE"),
                        MediaType.create("text", "plain").withParameter("A", "VALUE"))
                .addEqualityGroup(
                        MediaType.create("text", "plain")
                                 .withParameters(ImmutableListMultimap.of("a", "1", "a", "2").asMap()),
                        MediaType.create("text", "plain")
                                 .withParameters(ImmutableListMultimap.of("a", "2", "a", "1").asMap()))
                .addEqualityGroup(MediaType.create("text", "csv"))
                .addEqualityGroup(MediaType.create("application", "atom+xml"))
                .testEquals();
    }

    @Test // Non-UTF-8 Charset
    public void testEquals_nonUtf8Charsets() {
        new EqualsTester()
                .addEqualityGroup(MediaType.create("text", "plain"))
                .addEqualityGroup(MediaType.create("text", "plain").withCharset(UTF_8))
                .addEqualityGroup(MediaType.create("text", "plain").withCharset(UTF_16))
                .testEquals();
    }

    @Test // com.google.common.testing.NullPointerTester
    public void testNullPointer() {
        final NullPointerTester tester = new NullPointerTester();
        tester.testAllPublicConstructors(MediaType.class);
        tester.testAllPublicStaticMethods(MediaType.class);
        tester.testAllPublicInstanceMethods(MediaType.parse("text/plain"));
    }

    @Test
    public void testToString() {
        assertEquals("text/plain", MediaType.create("text", "plain").toString());
        assertEquals(
                "text/plain; something=\"cr@zy\"; something-else=\"crazy with spaces\"",
                MediaType.create("text", "plain")
                         .withParameter("something", "cr@zy")
                         .withParameter("something-else", "crazy with spaces")
                         .toString());
    }
}
