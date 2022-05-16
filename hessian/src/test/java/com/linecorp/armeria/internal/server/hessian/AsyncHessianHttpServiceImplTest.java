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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.hessian.service.AsyncHelloService;
import com.linecorp.armeria.hessian.service.AsyncHelloServiceImp;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.hessian.HessianHttpService;

/**
 * async api.
 *
 * @author eisig
 */
public class AsyncHessianHttpServiceImplTest extends AbstractHessianHttpServiceImplTest {

    @Override
    protected HessianHttpService setupHessianHttpService() {
        return HessianHttpService.builder()
                                 .addService("/services/helloService.hs", AsyncHelloService.class,
                                             new AsyncHelloServiceImp())
                                 .addService("/services/helloService2.hs", AsyncHelloService.class,
                                             new AsyncHelloServiceImp(), false).build();
    }

    @ParameterizedTest
    @ValueSource(strings = { "java.api.class", "java.home.class", "java.object.class" })
    @Override
    void testAttributeRequest(String attrName) throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "_hessian_getAttribute", attrName);
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final String reply = readReply(String.class, aggregatedRes.content().array());
        assertThat(reply).isEqualTo("com.linecorp.armeria.hessian.service.AsyncHelloService");
    }
}
