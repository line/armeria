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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;

class WrappingTransientHttpServiceTest {

    static final HttpService fooService = (ctx, req) -> HttpResponse.of("foo");

    @Test
    void extractTransientServiceOptions() {
        final HttpService wrapped = fooService.decorate(
                TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));

        @SuppressWarnings("rawtypes")
        final TransientService transientService = wrapped.as(TransientService.class);
        assertThat(transientService).isNotNull();

        @SuppressWarnings("unchecked")
        final Set<TransientServiceOption> transientServiceOptions =
                (Set<TransientServiceOption>) transientService.transientServiceOptions();
        assertThat(transientServiceOptions).containsExactly(TransientServiceOption.WITH_ACCESS_LOGGING);
    }
}
