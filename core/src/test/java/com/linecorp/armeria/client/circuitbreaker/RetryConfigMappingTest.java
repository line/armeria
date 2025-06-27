/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryConfigMapping;
import com.linecorp.armeria.client.retry.RetryConfigMappingBuilder;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;

import io.netty.util.AttributeKey;

class RetryConfigMappingTest {
    @Test
    void createsCorrectMappingWithPerMethod() {
        final RetryConfig configForGet = RetryConfig.builder(RetryRule.failsafe()).build();
        final RetryConfig configForPost = RetryConfig.noRetry();

        final RetryConfigMapping<?> mapping = RetryConfigMapping.perMethod(method -> {
            switch (method) {
                case "GET":
                    return configForGet;
                case "POST":
                    return configForPost;
                default:
                    fail();
                    // Make compiler happy.
                    return configForGet;
            }
        });

        final RetryConfigMapping<?> mappingFromBuilder = RetryConfigMapping.builder()
                                                                           .perMethod()
                                                                           .build(
                                                                                   (host, method, path) -> {
                                                                                       assertThat(
                                                                                               method).isNotNull();

                                                                                       switch (method) {
                                                                                           case "GET":
                                                                                               return configForGet;
                                                                                           case "POST":
                                                                                               return configForPost;
                                                                                           default:
                                                                                               fail();
                                                                                               // Make compiler happy.
                                                                                               return configForGet;
                                                                                       }
                                                                                   }
                                                                           );

        final ClientRequestContext requestContextForGet = createHttpContext("example.com", HttpMethod.GET,
                                                                            "/anypath");
        final ClientRequestContext requestContextForPost = createHttpContext("example.com", HttpMethod.POST,
                                                                             "/anypath");

        assertThat(mapping.get(requestContextForGet, requestContextForGet.request()))
                .isEqualTo(configForGet);
        assertThat(mapping.get(requestContextForPost, requestContextForPost.request()))
                .isEqualTo(configForPost);

        assertThat(mappingFromBuilder.get(requestContextForGet, requestContextForGet.request()))
                .isEqualTo(configForGet);
        assertThat(mappingFromBuilder.get(requestContextForPost, requestContextForPost.request()))
                .isEqualTo(configForPost);
    }

    @Test
    void createsCorrectMappingWithPerRpcMethod() {
        final RetryConfig configForQuery = RetryConfig.builder(RetryRule.failsafe()).build();
        final RetryConfig configForMutation = RetryConfig.noRetry();

        final RetryConfigMapping<?> mapping = RetryConfigMapping.perMethod(method -> {
            switch (method) {
                case "query":
                    return configForQuery;
                case "mutate":
                    return configForMutation;
                default:
                    fail();
                    // Make compiler happy.
                    return configForQuery;
            }
        });

        final RetryConfigMapping<?> mappingFromBuilder = RetryConfigMapping.builder()
                                                                           .perMethod()
                                                                           .build(
                                                                                   (host, method, path) -> {
                                                                                       assertThat(
                                                                                               method).isNotNull();

                                                                                       switch (method) {
                                                                                           case "query":
                                                                                               return configForQuery;
                                                                                           case "mutate":
                                                                                               return configForMutation;
                                                                                           default:
                                                                                               fail();
                                                                                               // Make compiler happy.
                                                                                               return configForQuery;
                                                                                       }
                                                                                   }
                                                                           );


        final ClientRequestContext requestContextForQuery = createRpcContext("example.com", TestService.class,
                                                                             "query");
        final ClientRequestContext requestContextForMutation = createRpcContext("example.com", TestService.class,
                                                                                "mutate");

        assertThat(mapping.get(requestContextForQuery, requestContextForQuery.rpcRequest()))
                .isEqualTo(configForQuery);
        assertThat(mapping.get(requestContextForMutation, requestContextForMutation.rpcRequest()))
                .isEqualTo(configForMutation);

        assertThat(mappingFromBuilder.get(requestContextForQuery, requestContextForQuery.rpcRequest()))
                .isEqualTo(configForQuery);
        assertThat(mappingFromBuilder.get(requestContextForMutation, requestContextForMutation.rpcRequest()))
                .isEqualTo(configForMutation);
    }

    @Test
    void createsCorrectMappingWithPerHost() {
        final RetryConfig configForExampleHost = RetryConfig.builder(RetryRule.failsafe()).build();
        final RetryConfig configForLocalHost = RetryConfig.noRetry();

        final RetryConfigMapping<?> mapping = RetryConfigMapping.perHost(host -> {
            switch (host) {
                case "example.com":
                    return configForExampleHost;
                case "localhost":
                    return configForLocalHost;
                default:
                    fail();
                    // Make compiler happy.
                    return configForExampleHost;
            }
        });

        final RetryConfigMapping<?> mappingFromBuilder = RetryConfigMapping.builder()
                                                                           .perHost()
                                                                           .build(
                                                                                   (host, method, path) -> {
                                                                                       assertThat(
                                                                                               host).isNotNull();

                                                                                       switch (host) {
                                                                                           case "example.com":
                                                                                               return configForExampleHost;
                                                                                           case "localhost":
                                                                                               return configForLocalHost;
                                                                                           default:
                                                                                               fail();
                                                                                               // Make compiler happy.
                                                                                               return configForExampleHost;
                                                                                       }
                                                                                   }
                                                                           );

        final ClientRequestContext requestContextForExample = createHttpContext("example.com", HttpMethod.GET,
                                                                                "/anypath");
        final ClientRequestContext requestContextForLocalhost = createHttpContext("localhost", HttpMethod.GET,
                                                                                  "/anypath");

        assertThat(mapping.get(requestContextForExample, requestContextForExample.request()))
                .isEqualTo(configForExampleHost);
        assertThat(mapping.get(requestContextForLocalhost, requestContextForLocalhost.request()))
                .isEqualTo(configForLocalHost);

        assertThat(mappingFromBuilder.get(requestContextForExample, requestContextForExample.request()))
                .isEqualTo(configForExampleHost);
        assertThat(mappingFromBuilder.get(requestContextForLocalhost, requestContextForLocalhost.request()))
                .isEqualTo(configForLocalHost);
    }

    @Test
    void createsCorrectMappingWithPerHostAndRpcRequest() {
        final RetryConfig configForProdHost = RetryConfig.builder(RetryRule.failsafe()).build();
        final RetryConfig configForTestHost = RetryConfig.noRetry();

        final RetryConfigMapping<?> mapping = RetryConfigMapping.perHost(host -> {
            switch (host) {
                case "api.prod.example.com":
                    return configForProdHost;
                case "api.test.example.com":
                    return configForTestHost;
                default:
                    fail();
                    // Make compiler happy.
                    return configForProdHost;
            }
        });

        final ClientRequestContext requestContextForProdHost = createRpcContext("api.prod.example.com",
                                                                                TestService.class, "query");
        final ClientRequestContext requestContextForTestHost = createRpcContext("api.test.example.com",
                                                                                TestService.class, "query");

        assertThat(mapping.get(requestContextForProdHost, requestContextForProdHost.rpcRequest()))
                .isEqualTo(configForProdHost);
        assertThat(mapping.get(requestContextForTestHost, requestContextForTestHost.rpcRequest()))
                .isEqualTo(configForTestHost);
    }

    @Test
    void createsCorrectMappingWithPerPath() {
        final RetryConfig configForApiPath = RetryConfig.builder(RetryRule.failsafe()).build();
        final RetryConfig configForHealthPath = RetryConfig.noRetry();

        final RetryConfigMapping<?> mapping = RetryConfigMapping.perPath(path -> {
            if (path.startsWith("/api/")) {
                return configForApiPath;
            } else if (path.startsWith("/health")) {
                return configForHealthPath;
            } else {
                fail();
                // Make compiler happy.
                return configForApiPath;
            }
        });

        final RetryConfigMapping<?> mappingFromBuilder = RetryConfigMapping.builder()
                                                                           .perPath()
                                                                           .build(
                                                                                   (host, method, path) -> {
                                                                                       assertThat(path).isNotNull();

                                                                                       if (path.startsWith("/api/")) {
                                                                                           return configForApiPath;
                                                                                       } else if (path.startsWith("/health")) {
                                                                                           return configForHealthPath;
                                                                                       } else {
                                                                                           fail();
                                                                                           // Make compiler happy.
                                                                                           return configForApiPath;
                                                                                       }
                                                                                   }
                                                                           );

        final HttpRequest apiRequest = HttpRequest.of(HttpMethod.GET, "/api/resources");
        final HttpRequest healthRequest = HttpRequest.of(HttpMethod.GET, "/health");

        final ClientRequestContext apiContext = createHttpContext("example.com", HttpMethod.GET, "/api/resources");
        final ClientRequestContext healthContext = createHttpContext("example.com", HttpMethod.GET, "/health");

        assertThat(mapping.get(apiContext, apiRequest)).isEqualTo(configForApiPath);
        assertThat(mapping.get(healthContext, healthRequest)).isEqualTo(configForHealthPath);

        assertThat(mappingFromBuilder.get(apiContext, apiRequest)).isEqualTo(configForApiPath);
        assertThat(mappingFromBuilder.get(healthContext, healthRequest)).isEqualTo(configForHealthPath);
    }

    @Test
    void createsCorrectMappingWithPerHostAndMethod() {
        final RetryConfig configForIpAddrGet =
                RetryConfig.builder(RetryRule.onException()).maxTotalAttempts(5).build();
        final RetryConfig configForIpAddrPost =
                RetryConfig.noRetry();


        // Duplicated just to see that we differentiate between the methods.
        final RetryConfig configForUnknownGet =
                RetryConfig.builder(RetryRule.onException()).maxTotalAttempts(10).build();
        // Not using RetryConfig.noRetry() here in order to have two different configs for configForIpAddrPost
        // and configForUnknownPost
        final RetryConfig configForUnknownPost = RetryConfig.builder(
                RetryRule.builder().onException().thenNoRetry()).build();


        final RetryConfigMapping<?> mapping = RetryConfigMapping.perHostAndMethod((host, method) -> {
            if ("192.168.1.1".equals(host)) {
                if ("GET".equals(method)) {
                    return configForIpAddrGet;
                } else if ("POST".equals(method)) {
                    return configForIpAddrPost;
                }
            } else if ("UNKNOWN".equals(host)) {
                if ("GET".equals(method)) {
                    return configForUnknownGet;
                } else if ("POST".equals(method)) {
                    return configForUnknownPost;
                }
            }
            fail();
            // Make compiler happy.
            return configForIpAddrGet;
        });

        final ClientRequestContext ipAddrGetContext = createHttpContext("192.168.1.1", HttpMethod.GET, "/api"
                                                                                                       + "/resources");
        final ClientRequestContext ipAddrPostContext = createHttpContext("192.168.1.1", HttpMethod.POST,
                                                                         "/api/resources");

        // Create contexts with null endpoint (will be handled as "UNKNOWN")
        final HttpRequest getRequest = HttpRequest.of(HttpMethod.GET, "/api/resources");
        final HttpRequest postRequest = HttpRequest.of(HttpMethod.POST, "/api/resources");

        final ClientRequestContext unknownGetContext =
                ClientRequestContext.builder(getRequest)
                        .endpointGroup(EndpointGroup.of())
                                    .build();

        final ClientRequestContext unknownPostContext =
                ClientRequestContext.builder(postRequest)
                                    .endpointGroup(EndpointGroup.of())
                                    .build();

        assertThat(mapping.get(ipAddrGetContext, getRequest)).isEqualTo(configForIpAddrGet);
        assertThat(mapping.get(ipAddrPostContext, postRequest)).isEqualTo(configForIpAddrPost);
        assertThat(mapping.get(unknownGetContext, getRequest)).isEqualTo(configForUnknownGet);
        assertThat(mapping.get(unknownPostContext, postRequest)).isEqualTo(configForUnknownPost);
    }


    @Test
    void createsCorrectMappingWithOf() {
        final AttributeKey<Integer> maxRetryAttemptsAttr =
                AttributeKey.valueOf("maxRetryAttemptsAttr");

        final RetryConfigMapping<?> mapping =
                RetryConfigMapping.of((ctx, req) -> (ctx.hasAttr(maxRetryAttemptsAttr) ?
                                                                       ctx.attr(maxRetryAttemptsAttr) :
                                                                       Integer.valueOf(1)).toString()
                        , (ctx, req) ->
                    RetryConfig.builder(
                                              RetryRule
                                                      .builder()
                                                      .onException()
                                                      .thenBackoff(Backoff.fixed(10))
                                      )
                                      .maxTotalAttempts(ctx.hasAttr(maxRetryAttemptsAttr) ? ctx.attr(maxRetryAttemptsAttr) : 1)
                                      .build()
        );


        final ClientRequestContext requestContextWithoutBackoffAttr =
                createHttpContext("a.example.com", HttpMethod.GET, "/foopath");
        final ClientRequestContext requestContextWithBackoffAttr = createRpcContext("b.example.com", TestService.class,
                                                                                 "query");
        requestContextWithBackoffAttr.setAttr(maxRetryAttemptsAttr, 42);

        final ClientRequestContext anotherRequestContextWithSameBackoffAttr =
                createRpcContext("c.example.com", TestService.class, "mutate");
        anotherRequestContextWithSameBackoffAttr.setAttr(maxRetryAttemptsAttr, 42);


        assertThat(mapping.get(requestContextWithoutBackoffAttr, requestContextWithoutBackoffAttr.request()).maxTotalAttempts())
                .isEqualTo(1);
        assertThat(mapping.get(requestContextWithBackoffAttr, requestContextWithBackoffAttr.request()).maxTotalAttempts())
                .isEqualTo(42);
        assertThat(mapping.get(anotherRequestContextWithSameBackoffAttr, anotherRequestContextWithSameBackoffAttr.request()).maxTotalAttempts())
                .isEqualTo(42);

        // Check that they are cached
        assertThat(mapping.get(requestContextWithBackoffAttr, requestContextWithBackoffAttr.request()))
                .isEqualTo(mapping.get(anotherRequestContextWithSameBackoffAttr, anotherRequestContextWithSameBackoffAttr.request()));
    }

    @Test
    void throwsExceptionWhenNoMappingKeysSet() {
        final RetryConfigMappingBuilder<HttpResponse> builder = RetryConfigMapping.builder();

        assertThat(assertThrows(
                IllegalStateException.class,
                () -> builder.build((host, method, path) -> RetryConfig.builder(RetryRule.failsafe()).build())
        ).getMessage()).isEqualTo("A RetryConfigMapping created by this builder must be per host, method and/or path");
    }

    private static ClientRequestContext createHttpContext(String host, HttpMethod method, String path) {
        final HttpRequest request = HttpRequest.of(method, path);
        return ClientRequestContext.builder(request)
                                   .endpointGroup(Endpoint.of(host))
                                   .build();
    }

    private static ClientRequestContext createRpcContext(String host, Class<?> serviceClass, String method) {
        final RpcRequest request = RpcRequest.of(serviceClass, method);
        return ClientRequestContext.builder(request, URI.create("http://" + host + ":80/testservice"))
                                   .endpointGroup(Endpoint.of(host))
                                   .build();
    }

    private static class TestService {}
}

