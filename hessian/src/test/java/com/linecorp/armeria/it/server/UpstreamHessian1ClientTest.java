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

import java.net.MalformedURLException;

import com.caucho.hessian.client.HessianProxyFactory;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.hessian.service.HelloService;

/**
 * test with upstream hessian1.
 *
 * @author eisig
 */
class UpstreamHessian1ClientTest extends AbstractHessianServerTest {

    @Override
    HelloService serviceCleinht() {
        final HessianProxyFactory factory = new HessianProxyFactory();
        try {
            return (HelloService) factory.create(HelloService.class,
                                                 "http://127.0.0.1:" + server.httpPort() +
                                                 "/services/helloService.hs");
        } catch (MalformedURLException e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    @Override
    HelloService serviceClientWithoutClass() {
        final HessianProxyFactory factory = new HessianProxyFactory();
        try {
            return (HelloService) factory.create(
                    "http://127.0.0.1:" + server.httpPort() + "/services/helloService.hs");
        } catch (MalformedURLException | ClassNotFoundException e) {
            return Exceptions.throwUnsafely(e);
        }
    }
}
