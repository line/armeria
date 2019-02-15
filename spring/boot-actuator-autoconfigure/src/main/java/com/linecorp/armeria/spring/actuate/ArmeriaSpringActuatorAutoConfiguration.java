/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.spring.actuate;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.http.ActuatorMediaType;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.Link;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

/**
 * A {@link Configuration} to enable actuator endpoints on an Armeria server. Corresponds to
 * {@link org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration}.
 */
@Configuration
@AutoConfigureAfter(EndpointAutoConfiguration.class)
@EnableConfigurationProperties(WebEndpointProperties.class)
public class ArmeriaSpringActuatorAutoConfiguration {

    private static final List<String> MEDIA_TYPES =
            ImmutableList.of(ActuatorMediaType.V2_JSON, "application/json");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Bean
    @ConditionalOnMissingBean
    EndpointMediaTypes endpointMediaTypes() {
        return new EndpointMediaTypes(MEDIA_TYPES, MEDIA_TYPES);
    }

    @Bean
    @ConditionalOnMissingBean(WebEndpointsSupplier.class)
    WebEndpointDiscoverer webEndpointDiscoverer(
            ApplicationContext applicationContext,
            ParameterValueMapper parameterValueMapper,
            EndpointMediaTypes endpointMediaTypes,
            ObjectProvider<PathMapper> endpointPathMappers,
            ObjectProvider<OperationInvokerAdvisor> invokerAdvisors,
            ObjectProvider<EndpointFilter<ExposableWebEndpoint>> filters) {
        return new WebEndpointDiscoverer(applicationContext,
                                         parameterValueMapper,
                                         endpointMediaTypes,
                                         endpointPathMappers.orderedStream().collect(toImmutableList()),
                                         invokerAdvisors.orderedStream().collect(toImmutableList()),
                                         filters.orderedStream().collect(toImmutableList()));
    }

    @Bean
    ArmeriaServerConfigurator actuatorServerConfigurator(
            WebEndpointsSupplier endpointsSupplier,
            EndpointMediaTypes mediaTypes,
            WebEndpointProperties properties) {
        final EndpointMapping endpointMapping = new EndpointMapping(properties.getBasePath());

        final Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
        return sb -> {
            endpoints.stream()
                     .flatMap(endpoint -> endpoint.getOperations().stream())
                     .forEach(operation -> {
                         final WebOperationRequestPredicate predicate = operation.getRequestPredicate();
                         sb.service(getPathMapping(predicate.getHttpMethod().name(),
                                                   endpointMapping.createSubPath(predicate.getPath()),
                                                   predicate.getConsumes(),
                                                   predicate.getProduces()),
                                    new WebOperationHttpService(operation));
                     });
            if (StringUtils.hasText(endpointMapping.getPath())) {
                final PathMapping mapping = getPathMapping(
                        HttpMethod.GET.name(),
                        endpointMapping.getPath(),
                        ImmutableList.of(),
                        mediaTypes.getProduced()
                );
                sb.service(mapping, (ctx, req) -> {
                    Map<String, Link> links = new EndpointLinksResolver(endpoints).resolveLinks(req.path());
                    return HttpResponse.of(
                            HttpStatus.OK,
                            MediaType.JSON,
                            OBJECT_MAPPER.writeValueAsBytes(ImmutableMap.of("_links", links))
                    );
                });
            }
        };
    }

    private static PathMapping getPathMapping(
            String method, String path, Collection<String> consumes, Collection<String> produces) {
        return PathMapping.of(path)
                          .withHttpHeaderInfo(
                                  ImmutableSet.of(HttpMethod.valueOf(method)),
                                  convertMediaTypes(consumes),
                                  convertMediaTypes(produces));
    }

    private static List<MediaType> convertMediaTypes(Iterable<String> mediaTypes) {
        return Streams.stream(mediaTypes).map(MediaType::parse).collect(toImmutableList());
    }
}
