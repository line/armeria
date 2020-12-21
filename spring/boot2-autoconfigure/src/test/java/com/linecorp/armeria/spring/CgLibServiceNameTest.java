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

package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.validation.annotation.Validated;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.spring.CgLibServiceNameTest.TestConfiguration;
import com.linecorp.armeria.spring.CgLibServiceNameTest.TestConfiguration.MyAnnotatedService;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class, webEnvironment = WebEnvironment.NONE)
@ActiveProfiles({ "local", "autoConfTest" })
class CgLibServiceNameTest {

    private static final AtomicReference<RequestLogAccess> logRef = new AtomicReference<>();

    @SpringBootApplication
    public static class TestConfiguration {

        @Validated
        @Component
        public class MyAnnotatedService {
            @Get("/hello")
            public HttpResponse world() {
                return HttpResponse.of(HttpStatus.OK);
            }
        }

        @Bean
        public ArmeriaServerConfigurator annotatedService(MyAnnotatedService annotatedService) {
            return sb -> {
                sb.annotatedService(annotatedService);
                sb.decorator((delegate, ctx, req) -> {
                    logRef.set(ctx.log());
                    return delegate.serve(ctx, req);
                });
            };
        }
    }

    @Inject
    private Server server;

    @Test
    void normalizedServiceName() throws InterruptedException {
        final WebClient client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort() + '/');
        client.get("/hello").aggregate().join();
        final RequestLog requestLog = logRef.get().whenComplete().join();
        assertThat(requestLog.serviceName()).isEqualTo(MyAnnotatedService.class.getName());
    }
}
