/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.throttling;

import static com.linecorp.armeria.server.throttling.ThrottlingStrategy.always;
import static com.linecorp.armeria.server.throttling.ThrottlingStrategy.never;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class ThrottlingServiceTest {

    static final HttpService SERVICE = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    };

    @Rule
    public ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/http-never", SERVICE.decorate(ThrottlingService.newDecorator(never())));
            sb.service("/http-always", SERVICE.decorate(ThrottlingService.newDecorator(always())));
        }
    };

    @Test
    public void serve() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        assertThat(client.get("/http-always").aggregate().get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    public void throttle() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        assertThat(client.get("/http-never").aggregate().get().status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
