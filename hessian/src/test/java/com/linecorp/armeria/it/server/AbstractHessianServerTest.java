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

package com.linecorp.armeria.it.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.hessian.service.DemoException;
import com.linecorp.armeria.hessian.service.HelloRequest;
import com.linecorp.armeria.hessian.service.HelloResponse;
import com.linecorp.armeria.hessian.service.HelloService;
import com.linecorp.armeria.hessian.service.HelloServiceImp;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.hessian.HessianHttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * common test.
 *
 * @author eisig
 */
abstract class AbstractHessianServerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/services/", setupHessianHttpService());
        }
    };

    static HessianHttpService setupHessianHttpService() {
        return HessianHttpService.builder()
                                 .addService("/helloService.hs", HelloService.class, new HelloServiceImp())
                                 .addService("/helloService2.hs", HelloService.class, new HelloServiceImp(),
                                             false)
                                 .build();
    }

    @Test
    void testSayHello() {
        final String reply = helloService().sayHello();
        assertThat(reply).isEqualTo("Hello");
    }

    @Test
    void testSayHello2() {
        final HelloResponse reply = helloService().sayHello2(new HelloRequest("JJ"));
        assertThat(reply).isEqualTo(new HelloResponse("Hello JJ"));
    }

    @Test
    void testServerImplError() {
        assertThatThrownBy(() -> helloService().failedSayHello(new HelloRequest("JJ")))
                .isInstanceOf(DemoException.class);
    }

    @Test
    void testServerWithUrl() {
        final HelloResponse reply = helloServiceCreateWithUrl().sayHello2(new HelloRequest("JJ"));
        assertThat(reply).isEqualTo(new HelloResponse("Hello JJ"));
    }

    abstract HelloService helloService();

    abstract HelloService helloServiceCreateWithUrl();
}
