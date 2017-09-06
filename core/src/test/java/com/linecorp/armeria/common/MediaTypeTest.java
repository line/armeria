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
import static com.linecorp.armeria.common.MediaType.HTML_UTF_8;
import static com.linecorp.armeria.common.MediaType.JPEG;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Streams;
import com.google.common.testing.EqualsTester;
import com.google.common.testing.NullPointerTester;

/**
 * Tests for {@link MediaType}.
 *
 * @author Gregory Kick
 */
public class MediaTypeTest {
    @Test // reflection
    public void testParse_useConstants() throws Exception {
        for (MediaType constant : getConstants()) {
            assertSame(constant, MediaType.parse(constant.toString()));
        }
    }

    @Test // reflection
    public void testCreate_useConstants() throws Exception {
        for (MediaType constant : getConstants()) {
            assertSame(constant, MediaType.create(constant.type(), constant.subtype())
                                          .withParameters(constant.parameters()));
        }
    }

    @Test // reflection
    public void testConstants_charset() throws Exception {
        for (Field field : getConstantFields()) {
            Optional<Charset> charset = ((MediaType) field.get(null)).charset();
            if (field.getName().endsWith("_UTF_8")) {
                assertThat(charset).hasValue(UTF_8);
            } else {
                assertThat(charset).isEmpty();
            }
        }
    }

    @Test // reflection
    public void testConstants_areUnique() {
        assertThat(getConstants()).doesNotHaveDuplicates();
    }

    // reflection
    private static Iterable<Field> getConstantFields() {
        return Arrays.stream(MediaType.class.getDeclaredFields()).filter((Predicate<Field>) input -> {
            int modifiers = input.getModifiers();
            return isPublic(modifiers) && isStatic(modifiers) && isFinal(modifiers) &&
                   MediaType.class.equals(input.getType());
        }).collect(Collectors.toList());
    }

    // reflection
    private static Iterable<MediaType> getConstants() {
        return Streams.stream(getConstantFields()).map(input -> {
            try {
                return (MediaType) input.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    @Test
    public void testCreate_invalidType() {
        assertThatThrownBy(() -> MediaType.create("te><t", "plaintext"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testCreate_invalidSubtype() {
        assertThatThrownBy(() -> MediaType.create("text", "pl@intext"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testCreate_wildcardTypeDeclaredSubtype() {
        assertThatThrownBy(() -> MediaType.create("*", "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testCreateApplicationType() {
        MediaType newType = MediaType.createApplicationType("yams");
        assertEquals("application", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateAudioType() {
        MediaType newType = MediaType.createAudioType("yams");
        assertEquals("audio", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateImageType() {
        MediaType newType = MediaType.createImageType("yams");
        assertEquals("image", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateTextType() {
        MediaType newType = MediaType.createTextType("yams");
        assertEquals("text", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testCreateVideoType() {
        MediaType newType = MediaType.createVideoType("yams");
        assertEquals("video", newType.type());
        assertEquals("yams", newType.subtype());
    }

    @Test
    public void testGetType() {
        assertEquals("text", MediaType.parse("text/plain").type());
        assertEquals("application",
                     MediaType.parse("application/atom+xml; charset=utf-8").type());
    }

    @Test
    public void testGetSubtype() {
        assertEquals("plain", MediaType.parse("text/plain").subtype());
        assertEquals("atom+xml",
                     MediaType.parse("application/atom+xml; charset=utf-8").subtype());
    }

    private static final Map<String, Collection<String>> PARAMETERS =
            ImmutableListMultimap.of("a", "1", "a", "2", "b", "3").asMap();

    @Test
    public void testGetParameters() {
        assertEquals(ImmutableMap.of(), MediaType.parse("text/plain").parameters());
        assertEquals(ImmutableMap.of("charset", ImmutableList.of("utf-8")),
                     MediaType.parse("application/atom+xml; charset=utf-8").parameters());
        assertEquals(PARAMETERS,
                     MediaType.parse("application/atom+xml; a=1; a=2; b=3").parameters());
    }

    @Test
    public void testWithoutParameters() {
        assertSame(MediaType.parse("image/gif"),
                   MediaType.parse("image/gif").withoutParameters());
        assertEquals(MediaType.parse("image/gif"),
                     MediaType.parse("image/gif; foo=bar").withoutParameters());
    }

    @Test
    public void testWithParameters() {
        assertEquals(MediaType.parse("text/plain; a=1; a=2; b=3"),
                     MediaType.parse("text/plain").withParameters(PARAMETERS));
        assertEquals(MediaType.parse("text/plain; a=1; a=2; b=3"),
                     MediaType.parse("text/plain; a=1; a=2; b=3").withParameters(PARAMETERS));
    }

    @Test
    public void testWithParameters_invalidAttribute() {
        MediaType mediaType = MediaType.parse("text/plain");
        Map<String, Collection<String>> parameters =
                ImmutableListMultimap.of("a", "1", "@", "2", "b", "3").asMap();
        assertThatThrownBy(() -> mediaType.withParameters(parameters))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWithParameter() {
        assertEquals(MediaType.parse("text/plain; a=1"),
                     MediaType.parse("text/plain").withParameter("a", "1"));
        assertEquals(MediaType.parse("text/plain; a=1"),
                     MediaType.parse("text/plain; a=1; a=2").withParameter("a", "1"));
        assertEquals(MediaType.parse("text/plain; a=3"),
                     MediaType.parse("text/plain; a=1; a=2").withParameter("a", "3"));
        assertEquals(MediaType.parse("text/plain; a=1; a=2; b=3"),
                     MediaType.parse("text/plain; a=1; a=2").withParameter("b", "3"));
    }

    @Test
    public void testWithParameter_invalidAttribute() {
        MediaType mediaType = MediaType.parse("text/plain");
        assertThatThrownBy(() -> mediaType.withParameter("@", "2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testWithCharset() {
        assertEquals(MediaType.parse("text/plain; charset=utf-8"),
                     MediaType.parse("text/plain").withCharset(UTF_8));
        assertEquals(MediaType.parse("text/plain; charset=utf-8"),
                     MediaType.parse("text/plain; charset=utf-16").withCharset(UTF_8));
    }

    @Test
    public void testHasWildcard() {
        Assert.assertFalse(PLAIN_TEXT_UTF_8.hasWildcard());
        Assert.assertFalse(JPEG.hasWildcard());
        Assert.assertTrue(ANY_TYPE.hasWildcard());
        Assert.assertTrue(ANY_APPLICATION_TYPE.hasWildcard());
        Assert.assertTrue(ANY_AUDIO_TYPE.hasWildcard());
        Assert.assertTrue(ANY_IMAGE_TYPE.hasWildcard());
        Assert.assertTrue(ANY_TEXT_TYPE.hasWildcard());
        Assert.assertTrue(ANY_VIDEO_TYPE.hasWildcard());
    }

    @Test
    public void testIs() {
        Assert.assertTrue(PLAIN_TEXT_UTF_8.is(ANY_TYPE));
        Assert.assertTrue(JPEG.is(ANY_TYPE));
        Assert.assertTrue(ANY_TEXT_TYPE.is(ANY_TYPE));
        Assert.assertTrue(PLAIN_TEXT_UTF_8.is(ANY_TEXT_TYPE));
        Assert.assertTrue(PLAIN_TEXT_UTF_8.withoutParameters().is(ANY_TEXT_TYPE));
        Assert.assertFalse(JPEG.is(ANY_TEXT_TYPE));
        Assert.assertTrue(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8));
        Assert.assertTrue(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8.withoutParameters()));
        Assert.assertFalse(PLAIN_TEXT_UTF_8.withoutParameters().is(PLAIN_TEXT_UTF_8));
        Assert.assertFalse(PLAIN_TEXT_UTF_8.is(HTML_UTF_8));
        Assert.assertFalse(PLAIN_TEXT_UTF_8.withParameter("charset", "UTF-16").is(PLAIN_TEXT_UTF_8));
        Assert.assertFalse(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8.withParameter("charset", "UTF-16")));
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
        assertThatThrownBy(() -> MediaType.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testParse_badInput() {
        assertThatThrownBy(() -> MediaType.parse("/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("te<t/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/pl@in"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain;"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=@"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=\"@"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=1;"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=1; "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=1; b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=1; b="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaType.parse("text/plain; a=\u2025"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testGetCharset() {
        assertThat(MediaType.parse("text/plain").charset()).isEmpty();
        assertThat(MediaType.parse("text/plain; charset=utf-8").charset()).hasValue(UTF_8);
    }

    @Test // Non-UTF-8 Charset
    public void testGetCharset_utf16() {
        assertThat(MediaType.parse("text/plain; charset=utf-16").charset()).hasValue(UTF_16);
    }

    @Test
    public void testGetCharset_tooMany() {
        MediaType mediaType = MediaType.parse("text/plain; charset=utf-8; charset=utf-16");
        assertThatThrownBy(mediaType::charset).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testGetCharset_illegalCharset() {
        MediaType mediaType = MediaType.parse(
                "text/plain; charset=\"!@#$%^&*()\"");
        assertThatThrownBy(mediaType::charset).isInstanceOf(IllegalCharsetNameException.class);
    }

    @Test
    public void testGetCharset_unsupportedCharset() {
        MediaType mediaType = MediaType.parse(
                "text/plain; charset=utf-wtf");
        assertThatThrownBy(mediaType::charset).isInstanceOf(UnsupportedCharsetException.class);
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
        NullPointerTester tester = new NullPointerTester();
        tester.testAllPublicConstructors(MediaType.class);
        tester.testAllPublicStaticMethods(MediaType.class);
        tester.testAllPublicInstanceMethods(MediaType.parse("text/plain"));
    }

    @Test
    public void testToString() {
        assertEquals("text/plain", MediaType.create("text", "plain").toString());
        assertEquals("text/plain; something=\"cr@zy\"; something-else=\"crazy with spaces\"",
                     MediaType.create("text", "plain")
                              .withParameter("something", "cr@zy")
                              .withParameter("something-else", "crazy with spaces")
                              .toString());
    }
}
