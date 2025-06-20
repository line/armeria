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

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.FieldAnn1;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.FieldAnn2;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.FieldAnn3;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.Inner1.BeanAnn1;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.ListFieldAnn1;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.FieldAnn4;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Inner4.BeanAnn4;

class BeanFieldInfoTest {

    @Test
    void getAnnotation() throws Exception {
        final Map<String, BeanFieldInfo> beanFieldInfoMap = new HashMap<>();
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final Child child = new Child();
        final String applied =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> {
                                    beanFieldInfoMap.put(info.name(), info);
                                    return FieldMasker.fallthrough();
                                })
                                .buildForText()
                                .apply(ctx, child);
        assertThatJson(applied).isEqualTo(
                '{' +
                "  \"inner1\" : {\"ann1\":\"ann1\"}," +
                "  \"ann2\" : \"ann2\"," +
                "  \"ann3\" : \"ann3\"," +
                "  \"inner4\" : {\"ann4\":\"ann4\"}" +
                '}');
        assertThat(beanFieldInfoMap).containsOnlyKeys("inner1", "ann1", "ann2", "ann3", "inner4", "ann4");
        assertThat(beanFieldInfoMap.get("inner1").getAnnotation(FieldAnn1.class)).isNotNull();
        assertThat(beanFieldInfoMap.get("inner1").getAnnotation(BeanAnn1.class)).isNotNull();

        // FieldAnn1 is not seen without @JacksonAnnotationsInside
        assertThat(beanFieldInfoMap.get("ann2").getAnnotation(FieldAnn1.class)).isNull();
        assertThat(beanFieldInfoMap.get("ann2").getAnnotation(FieldAnn2.class)).isNotNull();

        assertThat(beanFieldInfoMap.get("ann3").getAnnotation(FieldAnn1.class)).isNull();
        assertThat(beanFieldInfoMap.get("ann3").getAnnotation(FieldAnn3.class)).isNotNull();
        assertThat(beanFieldInfoMap.get("ann3").getAnnotation(ListFieldAnn1.class)).isNotNull();
        assertThat(beanFieldInfoMap.get("ann3").getAnnotation(ListFieldAnn1.class)
                                   .value()).hasSize(2);

        assertThat(beanFieldInfoMap.get("inner4").getAnnotation(FieldAnn4.class)).isNotNull();
        assertThat(beanFieldInfoMap.get("inner4").getAnnotation(BeanAnn4.class)).isNotNull();
    }
}
