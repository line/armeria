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

import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.resteasy.ArmeriaResteasyClientBuilder;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.resteasy.CustomRequestContext;
import testing.resteasy.jaxrs.samples.JaxRsApp;

@GenerateNativeImageTrace
class CalculatorServiceClientServerTest {

    private static final Logger logger = LoggerFactory.getLogger(CalculatorServiceClientServerTest.class);

    @RegisterExtension
    static final ServerExtension restServer = new ServerExtension() {
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
        final WebTarget webTarget = newWebTarget();

        final String contextPath = "/resteasy/app/calc/context";
        final Response context = webTarget.path(contextPath).request(MediaType.TEXT_PLAIN_TYPE)
                                          .cookie("param1", "1234")
                                          .cookie("other", "xyz")
                                          .get();
        assertThat(context.getStatus()).isEqualTo(Status.OK.getStatusCode());
        assertThat(context.getMediaType()).isNull();
        assertThat(context.hasEntity()).isFalse();
        final Map<String, NewCookie> cookies = context.getCookies();
        assertThat(cookies).containsOnly(
                Maps.immutableEntry("serverCookie", NewCookie.valueOf("serverCookie=123")));
    }

    @Test
    void testCalc() throws Exception {
        final WebTarget webTarget = newWebTarget();

        final String sumPath = "/resteasy/app/calc/sum";
        final Response sum = webTarget.path(sumPath)
                                      .queryParam("x", "10")
                                      .queryParam("y", "5")
                                      .request(MediaType.TEXT_PLAIN_TYPE).get();
        assertThat(sum.getStatus()).isEqualTo(Status.OK.getStatusCode());
        assertThat(sum.getMediaType()).isEqualTo(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"));
        assertThat(sum.readEntity(String.class)).isEqualTo("15");

        final String divPath = "/resteasy/app/calc/div";
        final Response div = webTarget.path(divPath)
                                      .queryParam("x", "10")
                                      .queryParam("y", "5")
                                      .request(MediaType.TEXT_PLAIN_TYPE).get();
        assertThat(div.getStatus()).isEqualTo(Status.OK.getStatusCode());
        assertThat(div.getMediaType()).isEqualTo(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"));
        assertThat(div.readEntity(String.class)).isEqualTo("2");
    }

    private static WebTarget newWebTarget() {
        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .failureResponseLogLevel(LogLevel.WARN)
                                             .build();
        final WebClientBuilder webClientBuilder = WebClient.builder()
                                                           .decorator(LoggingClient.builder()
                                                                                   .logWriter(logWriter)
                                                                                   .newDecorator());
        final Client restClient = ArmeriaResteasyClientBuilder.newBuilder(webClientBuilder).build();
        return restClient.target(restServer.httpUri());
    }
}
