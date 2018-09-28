/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.HEADER_PARAM;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.PATH_PARAM;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.QUERY_PARAM;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.extractParameter;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.isHidden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.models.parameters.Parameter;

public class AnnotatedHttpDocServiceUtilTest {

    @Test
    public void hiddenClassAndMethod() {
        final AnnotatedHttpService hiddenClass = mock(AnnotatedHttpService.class);
        when(hiddenClass.object()).thenReturn(new HiddenClass());
        assertThat(isHidden(hiddenClass)).isTrue();

        final AnnotatedHttpService hiddenMethod = mock(AnnotatedHttpService.class);
        when(hiddenMethod.object()).thenReturn(new HiddenMethod());
        when(hiddenMethod.method()).thenReturn(HiddenMethod.class.getDeclaredMethods()[0]);
        assertThat(isHidden(hiddenMethod)).isTrue();
    }

    @Test
    public void parameter() throws Exception {
        Method method = Service.class.getDeclaredMethod("param", String.class);
        AnnotatedValueResolver resolver = AnnotatedValueResolver.of(method, ImmutableSet.of(),
                                                                    ImmutableList.of()).get(0);
        Parameter parameter = extractParameter(resolver);
        assertThat(parameter).isNotNull();
        assertThat(parameter.getName()).isEqualTo("a1");
        assertThat(parameter.getIn()).isEqualTo(QUERY_PARAM);
        assertThat(parameter.getRequired()).isTrue();

        method = Service.class.getDeclaredMethod("param", Optional.class);
        resolver = AnnotatedValueResolver.of(method, ImmutableSet.of(), ImmutableList.of()).get(0);
        parameter = extractParameter(resolver);
        assertThat(parameter).isNotNull();
        assertThat(parameter.getName()).isEqualTo("a2");
        assertThat(parameter.getIn()).isEqualTo(QUERY_PARAM);
        assertThat(parameter.getRequired()).isFalse();

        method = Service.class.getDeclaredMethod("header", String.class);
        resolver = AnnotatedValueResolver.of(method, ImmutableSet.of(), ImmutableList.of()).get(0);
        parameter = extractParameter(resolver);
        assertThat(parameter).isNotNull();
        assertThat(parameter.getName()).isEqualTo("a3");
        assertThat(parameter.getIn()).isEqualTo(HEADER_PARAM);
        assertThat(parameter.getRequired()).isTrue();

        method = Service.class.getDeclaredMethod("pathParam", String.class);
        resolver = AnnotatedValueResolver.of(method, ImmutableSet.of("a4"), ImmutableList.of()).get(0);
        parameter = extractParameter(resolver);
        assertThat(parameter).isNotNull();
        assertThat(parameter.getName()).isEqualTo("a4");
        assertThat(parameter.getIn()).isEqualTo(PATH_PARAM);
        assertThat(parameter.getRequired()).isTrue();
    }

    @Hidden
    private static class HiddenClass {}

    private static class HiddenMethod {
        @Hidden
        void hiddenMethod() {}
    }

    private static class Service {

        @Get("/param")
        void param(@Param("a1") String a1) {}

        @Get("/param")
        void param(@Param("a2") Optional<String> a2) {}

        @Get("/header")
        void header(@Header("a3") String a3) {}

        @Get("/pathParam/{a4}")
        void pathParam(@Param("a4") String a4) {}
    }
}
