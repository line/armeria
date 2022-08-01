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

package com.linecorp.armeria.internal.server.hessian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.hessian.service.HelloService;
import com.linecorp.armeria.hessian.service.HelloServiceImp;

/**
 * test metadata parse.
 *
 * @author eisig
 */
class HessianServiceMetadataTest {

    @Test
    void function() {
        final HelloServiceImp impl = new HelloServiceImp();
        final HessianServiceMetadata metadata = new HessianServiceMetadata(HelloService.class, impl);
        assertThat(metadata.method("sayHello")).isNotNull();
        assertThat(metadata.method("sayHello2")).isNotNull();
        assertThat(metadata.method("sayHello2__1")).isNotNull();
        assertThat(metadata.method("sayHello2_HelloRequest")).isNotNull();
        assertThat(metadata.serviceType()).isEqualTo(HelloService.class);
        assertThat(metadata.method("sayHello2").getImplementation()).isSameAs(impl);
    }

    @Test
    void testObjectMustImplApi() {
        assertThatThrownBy(() -> new HessianServiceMetadata(HelloService.class, new Object()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not instance of");
    }
}
