/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.client.hessian;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.hessian.service.HelloService;
import com.linecorp.armeria.internal.common.hessian.HessianFunction;
import com.linecorp.armeria.internal.common.hessian.HessianFunction.ResponseType;

/**
 * test metadata.
 *
 * @author eisig
 */
class HessianServiceMetadataTest {

    @Test
    void testInterface() {
        final HessianServiceClientMetadata metadata = new HessianServiceClientMetadata(HelloService.class);
        assertThat(metadata).isNotNull();
        assertThat(metadata.apiClass()).isEqualTo(HelloService.class);
        assertThat(metadata.function("sayHello")).isNotNull();
        assertThat(metadata.function("sayHello2")).isNotNull();

        final HessianFunction sayHello = metadata.function("sayHello");
        assertThat(sayHello).hasFieldOrPropertyWithValue("responseType", ResponseType.OTHER_OBJECTS)
                            .hasFieldOrPropertyWithValue("serviceType", HelloService.class)
                            .hasFieldOrPropertyWithValue("name", "sayHello").hasFieldOrPropertyWithValue(
                                    "implementation", null);
    }

    @Test
    void testInterfaceWithOverloadEnabled() {
        final HessianServiceClientMetadata metadata = new HessianServiceClientMetadata(HelloService.class,
                                                                                       true);
        assertThat(metadata).isNotNull();
        assertThat(metadata.apiClass()).isEqualTo(HelloService.class);
        assertThat(metadata.function("sayHello")).isNotNull();
        assertThat(metadata.function("sayHelloStr_string")).isNotNull();
        assertThat(metadata.function("sayHello2_HelloRequest")).isNotNull();

        final HessianFunction sayHello = metadata.function("sayHello");
        assertThat(sayHello).hasFieldOrPropertyWithValue("responseType", ResponseType.OTHER_OBJECTS)
                            .hasFieldOrPropertyWithValue("serviceType", HelloService.class)
                            .hasFieldOrPropertyWithValue("name", "sayHello").hasFieldOrPropertyWithValue(
                                    "implementation", null);
    }
}
