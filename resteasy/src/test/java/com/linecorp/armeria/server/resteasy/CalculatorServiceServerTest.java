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

package com.linecorp.armeria.server.resteasy;

import static org.assertj.core.api.Assertions.assertThat;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.jaxrs.samples.JaxRsApp;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class CalculatorServiceServerTest {

    private static final Logger logger = LoggerFactory.getLogger(CalculatorServiceServerTest.class);

    @RegisterExtension
    static ServerExtension restServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder serverBuilder) throws Exception {
            logger.info("Configuring HTTP Server with RESTEasy Service");
            serverBuilder.accessLogger(logger);
            final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
            //deployment.setApplicationClass(JaxRsApp.class.getName());
            deployment.setApplication(new JaxRsApp());
            ResteasyService.<CustomRequestContext>builder(deployment)
                    .path("/resteasy")
                    .requestContextConverter(CustomRequestContext.class, CustomRequestContext::new)
                    .build().register(serverBuilder);
        }
    };

    @Test
    void testCalcContext() throws Exception {
        final WebClient restClient = newWebClient();

        final String contextPath = "/resteasy/app/calc/context";
        final AggregatedHttpResponse context = restClient
                .execute(RequestHeaders.builder(HttpMethod.GET, contextPath)
                                       .add(HttpHeaderNames.COOKIE, "param1=1234; other=xyz").build())
                .aggregate()
                .join();
        logger.info("{} responded with {}", contextPath, context.contentUtf8());
        assertThat(context.status()).isEqualTo(HttpStatus.OK);
        assertThat(context.contentType()).isNull();
        assertThat(context.content().isEmpty()).isTrue();
        final Cookies cookies =
            Cookie.fromSetCookieHeaders(context.headers().getAll(HttpHeaderNames.SET_COOKIE));
        assertThat(cookies).containsOnly(Cookie.ofSecure("serverCookie", "123"));
    }

    @Test
    void testCalc() throws Exception {
        final WebClient restClient = newWebClient();

        final String sumPath = "/resteasy/app/calc/sum";
        final AggregatedHttpResponse sum = restClient
                .get(sumPath + "?x=10&y=5")
                .aggregate()
                .join();
        logger.info("{} responded with {}", sumPath, sum.contentUtf8());
        assertThat(sum.status()).isEqualTo(HttpStatus.OK);
        assertThat(sum.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(sum.contentUtf8()).isEqualTo("15");

        final String divPath = "/resteasy/app/calc/div";
        final AggregatedHttpResponse div = restClient
                .get(divPath + "?x=10&y=5")
                .aggregate()
                .join();
        logger.info("{} responded with {}", divPath, div.contentUtf8());
        assertThat(div.status()).isEqualTo(HttpStatus.OK);
        assertThat(div.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(div.contentUtf8()).isEqualTo("2");
    }

    private static WebClient newWebClient() {
        return WebClient.builder(restServer.httpUri())
                        .decorator(LoggingClient.builder()
                                                .logger(logger)
                                                .requestLogLevel(LogLevel.INFO)
                                                .successfulResponseLogLevel(LogLevel.INFO)
                                                .failureResponseLogLevel(LogLevel.WARN)
                                                .newDecorator())
                        .build();
    }
}
