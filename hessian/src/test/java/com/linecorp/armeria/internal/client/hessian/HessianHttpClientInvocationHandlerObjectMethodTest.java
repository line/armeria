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

import com.caucho.hessian.io.HessianRemoteObject;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.hessian.service.HelloService;

/**
 * invoke method declaring in Object.class
 *
 * @author eisig
 */
class HessianHttpClientInvocationHandlerObjectMethodTest {

    @Test
    void testObjectMethod() {
        final HelloService client = Clients.builder("hessian+http://127.0.0.1:1/helloService.hs").build(
                HelloService.class);
        final HelloService client2 = Clients.builder("hessian+http://127.0.0.1:1/helloService.hs").build(
                HelloService.class);

        assertThat(client.toString()).isEqualTo("HelloService(/helloService.hs)");
        assertThat(client).isNotEqualTo(client2);
    }

    @Test
    void testHessianRemoteObjectMethod() {
        final HessianRemoteObject client = (HessianRemoteObject) Clients.builder(
                                                                                "hessian+http://127.0.0.1:1/helloService.hs")
                                                                        .build(HelloService.class);

        assertThat(client.toString()).isEqualTo("HelloService(/helloService.hs)");
        assertThat(client.getHessianType()).isEqualTo("com.linecorp.armeria.hessian.service.HelloService");
        assertThat(client.getHessianURL()).isEqualTo("hessian+http://127.0.0.1:1/helloService.hs");
    }
}
