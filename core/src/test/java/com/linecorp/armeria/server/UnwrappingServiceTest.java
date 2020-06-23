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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class UnwrappingServiceTest {
    @Test
    void unwrap() {
        final MyService myService = new MyService();
        final MyDecoratorA myDecoratorA = new MyDecoratorA(myService);
        final MyDecoratorB myDecoratorB = new MyDecoratorB(myDecoratorA);
        assertThat(myService.unwrap()).isSameAs(myService);
        assertThat(myDecoratorA.unwrap()).isSameAs(myService);
        assertThat(myDecoratorB.unwrap()).isSameAs(myDecoratorA);
        assertThat(myDecoratorB.unwrap().unwrap()).isSameAs(myService);
        assertThat(myDecoratorB.unwrap().unwrap().unwrap().unwrap()).isSameAs(myService);
    }

    private static final class MyService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    private static final class MyDecoratorA extends SimpleDecoratingHttpService {
        MyDecoratorA(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req);
        }
    }

    private static final class MyDecoratorB extends SimpleDecoratingHttpService {
        MyDecoratorB(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req);
        }
    }
}
