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
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

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
        getConstants().forEach(constant -> {
            assertThat(MediaType.parse(constant.toString())).isSameAs(constant);
        });
    }

    @Test // reflection
    public void testCreate_useConstants() throws Exception {
        getConstants().forEach(constant -> {
            assertThat(MediaType.create(constant.type(), constant.subtype())
                             .withParameters(constant.parameters())).isSameAs(constant);
        });
    }

    @Test // reflection
    public void testConstants_charset() throws Exception {
        for (Field field: getConstantFieldsList(MediaType.class)) {
            final Charset charset = ((MediaType) field.get(null)).charset();
            if (field.getName().endsWith("_UTF_8")) {
                assertThat(charset).isEqualTo(UTF_8);
            } else {
                assertThat(charset).isNull();
            }
        }
    }

    @Test // reflection
    void testConstants_areUnique() {
        assertThat(getConstants()).doesNotHaveDuplicates();
    }

    // reflection
    static <T, R> Stream<Field> getConstantFields(Class<T> clazz, Class<R>... filterClazz) {
        return asList(clazz.getDeclaredFields()).stream().filter(input -> {
            final int modifiers = input.getModifiers();
            return isPublic(modifiers) &&
                   isStatic(modifiers) &&
                   isFinal(modifiers) &&
                   filterClazz.length == 1 ?
                   filterClazz[0].equals(input.getType()) : clazz.equals(input.getType());
        });
    }

    // reflection
    private static <T, R> List<Field> getConstantFieldsList(Class<T> clazz, Class<R>... filterClazz) {
        return getConstantFields(clazz, filterClazz).collect(Collectors.toList());
    }

    // reflection
    @SuppressWarnings("unchecked")
    private static <T> Stream<T> getConstants(Class<T> clazz) {
        return getConstantFields(clazz).map(input -> {
            try {
                return (T) input.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // reflection
    private static Stream<MediaType> getConstants() {
        return getConstantFields(MediaType.class).map(input -> {
            try {
                return (MediaType) input.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testCreate_invalidType() {
        try {
            MediaType.create("te><t", "plaintext");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void testCreate_invalidSubtype() {
        try {
            MediaType.create("text", "pl@intext");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void testCreate_wildcardTypeDeclaredSubtype() {
        try {
            MediaType.create("*", "text");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void testCreateApplicationType() {
        final MediaType newType = MediaType.createApplicationType("yams");
        assertThat(newType.type()).isEqualTo("application");
        assertThat(newType.subtype()).isEqualTo("yams");
    }

    @Test
    void testCreateAudioType() {
        final MediaType newType = MediaType.createAudioType("yams");
        assertThat(newType.type()).isEqualTo("audio");
        assertThat(newType.subtype()).isEqualTo("yams");
    }

    @Test
    void testCreateImageType() {
        final MediaType newType = MediaType.createImageType("yams");
        assertThat(newType.type()).isEqualTo("image");
        assertThat(newType.subtype()).isEqualTo("yams");
    }

    @Test
    void testCreateTextType() {
        final MediaType newType = MediaType.createTextType("yams");
        assertThat(newType.type()).isEqualTo("text");
        assertThat(newType.subtype()).isEqualTo("yams");
    }

    @Test
    void testCreateVideoType() {
        final MediaType newType = MediaType.createVideoType("yams");
        assertThat(newType.type()).isEqualTo("video");
        assertThat(newType.subtype()).isEqualTo("yams");
    }

    @Test
    void testGetType() {
        assertThat(MediaType.parse("text/plain").type()).isEqualTo("text");
        assertThat(MediaType.parse("application/atom+xml; charset=utf-8").type()).isEqualTo("application");
    }

    @Test
    void testGetSubtype() {
        assertThat(MediaType.parse("text/plain").subtype()).isEqualTo("plain");
        assertThat(MediaType.parse("application/atom+xml; charset=utf-8").subtype()).isEqualTo("atom+xml");
    }

    private static final Map<String, Collection<String>> PARAMETERS =
            ImmutableListMultimap.of("a", "1", "a", "2", "b", "3").asMap();

    @Test
    void testGetParameters() {
        assertThat(MediaType.parse("text/plain").parameters()).isEqualTo(ImmutableMap.of());
        assertThat(MediaType.parse("application/atom+xml; charset=utf-8").parameters())
                .isEqualTo(ImmutableMap.of("charset", ImmutableList.of("utf-8")));
        assertThat(MediaType.parse("application/atom+xml; a=1; a=2; b=3").parameters())
                .isEqualTo(PARAMETERS);
    }

    @Test
    void testWithoutParameters() {
        assertThat(MediaType.parse("image/gif").withoutParameters())
                .isSameAs(MediaType.parse("image/gif"));
        assertThat(MediaType.parse("image/gif"))
                .isEqualTo(MediaType.parse("image/gif; foo=bar").withoutParameters());
    }

    @Test
    void testWithParameters() {
        assertThat(MediaType.parse("text/plain").withParameters(PARAMETERS))
                .isEqualTo(MediaType.parse("text/plain; a=1; a=2; b=3"));
        assertThat(MediaType.parse("text/plain; a=1; a=2; b=3").withParameters(PARAMETERS))
                .isEqualTo(MediaType.parse("text/plain; a=1; a=2; b=3"));
    }

    @Test
    void testWithParameters_invalidAttribute() {
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
    void testWithParameter() {
        assertThat(MediaType.parse("text/plain").withParameter("a", "1"))
                .isEqualTo(MediaType.parse("text/plain; a=1"));
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameter("a", "1"))
                .isEqualTo(MediaType.parse("text/plain; a=1"));
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameter("a", "3"))
                .isEqualTo(MediaType.parse("text/plain; a=3"));
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameter("b", "3"))
                .isEqualTo(MediaType.parse("text/plain; a=1; a=2; b=3"));
    }

    @Test
    void testWithParameter_invalidAttribute() {
        final MediaType mediaType = MediaType.parse("text/plain");
        try {
            mediaType.withParameter("@", "2");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void testWithParametersIterable() {
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameters("a", ImmutableSet.of()))
                .isEqualTo(MediaType.parse("text/plain"));
        assertThat(MediaType.parse("text/plain").withParameters("a", ImmutableSet.of("1")))
                .isEqualTo(MediaType.parse("text/plain; a=1"));
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameters("a", ImmutableSet.of("1")))
                .isEqualTo(MediaType.parse("text/plain; a=1"));
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameters("a", ImmutableSet.of("1", "3")))
                .isEqualTo(MediaType.parse("text/plain; a=1; a=3"));
        assertThat(MediaType.parse("text/plain; a=1; a=2").withParameters("b", ImmutableSet.of("3", "4")))
                .isEqualTo(MediaType.parse("text/plain; a=1; a=2; b=3; b=4"));
    }

    @Test
    void testWithParametersIterable_invalidAttribute() {
        final MediaType mediaType = MediaType.parse("text/plain");
        try {
            mediaType.withParameters("@", ImmutableSet.of("2"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void testWithParametersIterable_nullValue() {
        final MediaType mediaType = MediaType.parse("text/plain");
        try {
            mediaType.withParameters("a", Collections.singletonList(null));
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    void testWithCharset() {
        assertThat(MediaType.parse("text/plain").withCharset(UTF_8))
                .isEqualTo(MediaType.parse("text/plain; charset=utf-8"));
        assertThat(MediaType.parse("text/plain; charset=utf-16").withCharset(UTF_8))
                .isEqualTo(MediaType.parse("text/plain; charset=utf-8"));
    }

    @Test
    void testHasWildcard() {
        assertThat(PLAIN_TEXT_UTF_8.hasWildcard()).isFalse();
        assertThat(JPEG.hasWildcard()).isFalse();
        assertThat(ANY_TYPE.hasWildcard()).isTrue();
        assertThat(ANY_APPLICATION_TYPE.hasWildcard()).isTrue();
        assertThat(ANY_AUDIO_TYPE.hasWildcard()).isTrue();
        assertThat(ANY_IMAGE_TYPE.hasWildcard()).isTrue();
        assertThat(ANY_TEXT_TYPE.hasWildcard()).isTrue();
        assertThat(ANY_VIDEO_TYPE.hasWildcard()).isTrue();
    }

    @Test
    void testIs() {
        assertThat(PLAIN_TEXT_UTF_8.is(ANY_TYPE)).isTrue();
        assertThat(JPEG.is(ANY_TYPE)).isTrue();
        assertThat(ANY_TEXT_TYPE.is(ANY_TYPE)).isTrue();
        assertThat(PLAIN_TEXT_UTF_8.is(ANY_TEXT_TYPE)).isTrue();
        assertThat(PLAIN_TEXT_UTF_8.withoutParameters().is(ANY_TEXT_TYPE)).isTrue();
        assertThat(JPEG.is(ANY_TEXT_TYPE)).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8)).isTrue();
        assertThat(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8.withoutParameters())).isTrue();
        assertThat(PLAIN_TEXT_UTF_8.withoutParameters().is(PLAIN_TEXT_UTF_8)).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.is(HTML_UTF_8)).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.withParameter("charset", "UTF-16").is(PLAIN_TEXT_UTF_8)).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8.withParameter("charset", "UTF-16"))).isFalse();
    }

    @Test
    void testIsJson() {
        assertThat(JSON.isJson()).isTrue();
        assertThat(JSON_UTF_8.isJson()).isTrue();
        assertThat(MediaType.parse("application/graphql+json").isJson()).isTrue();
        assertThat(PLAIN_TEXT_UTF_8.isJson()).isFalse();
        assertThat(GRAPHQL.isJson()).isFalse();
    }

    @Test
    void testBelongsTo() {
        // For quality factor, "belongsTo" has a different behavior to "is".
        assertThat(PLAIN_TEXT_UTF_8.is(ANY_TYPE.withParameter("q", "0.9"))).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.belongsTo(ANY_TYPE.withParameter("q", "0.9"))).isTrue();

        // For the other parameters, "belongsTo" has the same behavior as "is".
        assertThat(PLAIN_TEXT_UTF_8.is(ANY_TYPE.withParameter("charset", "UTF-16"))).isFalse();
        assertThat(PLAIN_TEXT_UTF_8.belongsTo(ANY_TYPE.withParameter("charset", "UTF-16"))).isFalse();
    }

    @Test
    void testParse_empty() {
        try {
            MediaType.parse("");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void testParse_badInput() {
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
    void testGetCharset() {
        assertThat(MediaType.parse("text/plain").charset()).isNull();
        assertThat(MediaType.parse("text/plain; charset=utf-8").charset()).isEqualTo(UTF_8);
    }

    @Test // Non-UTF-8 Charset
    void testGetCharset_utf16() {
        assertThat(MediaType.parse("text/plain; charset=utf-16").charset()).isEqualTo(UTF_16);
    }

    @Test
    void testGetCharset_tooMany() {
        final MediaType mediaType = MediaType.parse("text/plain; charset=utf-8; charset=utf-16");
        try {
            mediaType.charset();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    void testGetCharset_illegalCharset() {
        final MediaType mediaType = MediaType.parse("text/plain; charset=\"!@#$%^&*()\"");
        try {
            mediaType.charset();
            fail();
        } catch (IllegalCharsetNameException expected) {
        }
    }

    @Test
    void testGetCharset_unsupportedCharset() {
        final MediaType mediaType = MediaType.parse("text/plain; charset=utf-wtf");
        try {
            mediaType.charset();
            fail();
        } catch (UnsupportedCharsetException expected) {
        }
    }

    @Test
    void testEquals() {
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
    void testEquals_nonUtf8Charsets() {
        new EqualsTester()
                .addEqualityGroup(MediaType.create("text", "plain"))
                .addEqualityGroup(MediaType.create("text", "plain").withCharset(UTF_8))
                .addEqualityGroup(MediaType.create("text", "plain").withCharset(UTF_16))
                .testEquals();
    }

    @Test // com.google.common.testing.NullPointerTester
    void testNullPointer() {
        final NullPointerTester tester = new NullPointerTester();
        tester.testAllPublicConstructors(MediaType.class);
        tester.testAllPublicStaticMethods(MediaType.class);
        tester.testAllPublicInstanceMethods(MediaType.parse("text/plain"));
    }

    @Test
    void testToString() {
        assertThat(MediaType.create("text", "plain").toString()).isEqualTo("text/plain");
        assertThat(MediaType.create("text", "plain")
                            .withParameter("something", "cr@zy")
                            .withParameter("something-else", "crazy with spaces")
                            .toString())
                .isEqualTo("text/plain; something=\"cr@zy\"; something-else=\"crazy with spaces\"");
    }

    @Test
    void wellDefinedUpstreamMediaTypes() {
        getConstants(com.google.common.net.MediaType.class).forEach(upstreamMediaType -> {
            // If upstreamMediaType is "well-known" in armeria, the same instance will be returned
            assertThat(MediaType.parse(upstreamMediaType.toString()))
                    .isSameAs(MediaType.parse(upstreamMediaType.toString()));
        });
    }
}
