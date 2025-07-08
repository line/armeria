/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.common.logging;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.Inner1;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Inner4;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.SimpleFoo;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.SimpleFoo.HelloFieldAnn;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.SimpleFoo.InnerFoo;
import com.linecorp.armeria.internal.common.JacksonUtil;

class MaskingSerdeTest {

    private static final ObjectMapper MAPPER = JacksonUtil.newDefaultObjectMapper();

    @Test
    void nullifySerde() throws Exception {
        final SimpleFoo simpleFoo = new SimpleFoo();

        final ObjectMapper objectMapper =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> FieldMasker.nullify())
                                .buildObjectMapper();
        final String ser = objectMapper.writer().writeValueAsString(simpleFoo);
        assertThatJson(ser).isEqualTo("{\"inner\":null}");
        final SimpleFoo deserialized = objectMapper.readValue(ser, SimpleFoo.class);
        assertThat(deserialized.inner).isNull();
    }

    @Test
    void maskingSerde() throws Exception {
        final SimpleFoo simpleFoo = new SimpleFoo();

        final BeanFieldMaskerSelector maskerSelector = FieldMaskerSelector.ofBean(
                info -> {
                    if (info.getAnnotation(HelloFieldAnn.class) == null) {
                        return FieldMasker.noMask();
                    }
                    return FieldMasker.builder()
                                      .addMasker(InnerFoo.class, obj -> {
                                          try {
                                              final String s = MAPPER.writer().writeValueAsString(obj);
                                              return s.replaceAll("\"", "!");
                                          } catch (JsonProcessingException e) {
                                              throw new RuntimeException(e);
                                          }
                                      }, s -> {
                                          s = s.replaceAll("!", "\"");
                                          try {
                                              return MAPPER.reader().readValue(s, InnerFoo.class);
                                          } catch (IOException e) {
                                              throw new RuntimeException(e);
                                          }
                                      })
                                      .build();
                });
        final ObjectMapper objectMapper =
                ContentSanitizer.builder()
                                .fieldMaskerSelector(maskerSelector)
                                .buildObjectMapper();
        final String ser = objectMapper.writer().writeValueAsString(simpleFoo);
        assertThat(ser).isEqualTo("{\"inner\":\"{!hello!:!world!,!intVal!:42,!masked!:!masked!}\"}");
        final SimpleFoo deserialized = objectMapper.readValue(ser, SimpleFoo.class);
        assertThat(deserialized).isEqualTo(simpleFoo);
    }

    @Test
    void defaultTypedFieldMasker() throws Exception {
        final SimpleFoo simpleFoo = new SimpleFoo();

        final BeanFieldMaskerSelector maskerSelector = FieldMaskerSelector.ofBean(
                info -> FieldMasker.builder()
                                   .addMasker(String.class, String::toUpperCase)
                                   .addMasker(SimpleFoo.class, o -> o)
                                   .addMasker(InnerFoo.class, o -> o)
                                   .build(FieldMasker.nullify()));
        final ObjectMapper objectMapper =
                ContentSanitizer.builder()
                                .fieldMaskerSelector(maskerSelector)
                                .buildObjectMapper();
        final String ser = objectMapper.writer().writeValueAsString(simpleFoo);
        assertThatJson(ser).node("inner.hello").isEqualTo("WORLD");
        assertThatJson(ser).node("inner.intVal").isEqualTo(null);
        final SimpleFoo des = objectMapper.readValue(ser, SimpleFoo.class);
        assertThat(des.inner.intVal).isZero();
    }

    @Test
    void complexFieldMasker() throws Exception {
        final SimpleFoo simpleFoo = new SimpleFoo();

        final BeanFieldMaskerSelector maskerSelector = FieldMaskerSelector.ofBean(
                info -> FieldMasker.builder()
                                   .addMasker(String.class, String::toUpperCase)
                                   .addMasker(SimpleFoo.class, o -> o)
                                   .addMasker(InnerFoo.class, o -> o)
                                   .build(FieldMasker.nullify()));
        final ObjectMapper objectMapper =
                ContentSanitizer.builder()
                                .fieldMaskerSelector(maskerSelector)
                                .buildObjectMapper();
        final String ser = objectMapper.writer().writeValueAsString(simpleFoo);
        assertThatJson(ser).node("inner.hello").isEqualTo("WORLD");
        assertThatJson(ser).node("inner.intVal").isEqualTo(null);
        final SimpleFoo des = objectMapper.readValue(ser, SimpleFoo.class);
        assertThat(des.inner.intVal).isZero();
    }

    @Test
    void mixedMaskerAndEncryptor() throws Exception {
        final Child child = new Child();

        final BeanFieldMaskerSelector maskerSelector = FieldMaskerSelector.ofBean(
                info -> FieldMasker.builder()
                                   .addMasker(String.class, String::toUpperCase)
                                   // won't reach this since the first matched matcher will be chosen
                                   .addMasker(String.class, s -> {
                                       throw new RuntimeException();
                                   })
                                   .addMasker(Inner4.class, o -> {
                                       try {
                                           final String s = MAPPER.writer().writeValueAsString(o);
                                           return s.replaceAll("\"", "!");
                                       } catch (JsonProcessingException e) {
                                           throw new RuntimeException(e);
                                       }
                                   }, s -> {
                                       s = s.replaceAll("!", "\"");
                                       try {
                                           return MAPPER.reader().readValue(s, Inner4.class);
                                       } catch (IOException e) {
                                           throw new RuntimeException(e);
                                       }
                                   })
                                   .addMasker(Inner1.class, o -> null)
                                   .addMasker(Inner4.class, o -> o)
                                   .build(FieldMasker.nullify()));
        final ObjectMapper objectMapper =
                ContentSanitizer.builder()
                                .fieldMaskerSelector(maskerSelector)
                                .buildObjectMapper();
        final String ser = objectMapper.writer().writeValueAsString(child);
        assertThatJson(ser).node("inner4").isStringEqualTo("{!ann4!:!ann4!}");
        assertThatJson(ser).node("inner1").isEqualTo(null);
        assertThatJson(ser).node("ann2").isEqualTo("ANN2");
        assertThatJson(ser).node("ann3").isEqualTo("ANN3");
        final Child des = objectMapper.readValue(ser, Child.class);
        assertThat(des.inner4.ann4).isEqualTo(child.inner4.ann4);
    }
}
