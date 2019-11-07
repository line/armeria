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

package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.MediaType;

class EndpointInfoBuilderTest {

    @Test
    void testBuild() {
        final EndpointInfoBuilder endpointInfoBuilder = EndpointInfo.builder("*", "/foo");
        final EndpointInfo endpointInfo = endpointInfoBuilder.availableMimeTypes(MediaType.JSON_UTF_8)
                                                             .build();
        assertThat(endpointInfo).isEqualTo(new EndpointInfo("*", "/foo", "", "", null,
                                                            ImmutableSet.of(MediaType.JSON_UTF_8)));
    }

    @Test
    void shouldHaveAtLeastOneMimeType() {
        final EndpointInfoBuilder endpointInfoBuilder = EndpointInfo.builder("*", "/foo");
        assertThatThrownBy(endpointInfoBuilder::build).isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotSetBothPrefixAndFragment() {
        final EndpointInfoBuilder endpointInfoBuilder = EndpointInfo.builder("*", "/foo");
        endpointInfoBuilder.regexPathPrefix("/prefix/");
        assertThatThrownBy(() -> endpointInfoBuilder.fragment("/fragment"))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void defaultTypeIsAddedToAvailableTypes() {
        EndpointInfoBuilder endpointInfoBuilder = EndpointInfo.builder("*", "/foo");
        // Add the defaultMiMeType first.
        endpointInfoBuilder.defaultMimeType(MediaType.JSON_UTF_8);
        endpointInfoBuilder.availableMimeTypes(MediaType.JSON_PATCH);
        EndpointInfo endpointInfo = endpointInfoBuilder.build();
        assertThat(endpointInfo.availableMimeTypes()).containsExactlyInAnyOrder(MediaType.JSON_UTF_8,
                                                                                MediaType.JSON_PATCH);

        endpointInfoBuilder = EndpointInfo.builder("*", "/foo");
        // Add the availableMimeTypes first.
        endpointInfoBuilder.availableMimeTypes(MediaType.JSON_PATCH);
        endpointInfoBuilder.defaultMimeType(MediaType.JSON_UTF_8);
        endpointInfo = endpointInfoBuilder.build();
        assertThat(endpointInfo.availableMimeTypes()).containsExactlyInAnyOrder(MediaType.JSON_UTF_8,
                                                                                MediaType.JSON_PATCH);
    }
}
