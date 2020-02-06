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
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.springframework.boot.actuate.health.HealthStatusHttpMapper;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

/**
 * A {@link Configuration} to enable actuator endpoints on an Armeria server. Corresponds to
 * {@link org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration}.
 */
@Configuration
@AutoConfigureAfter(EndpointAutoConfiguration.class)
@EnableConfigurationProperties({ WebEndpointProperties.class, CorsEndpointProperties.class })
public class ArmeriaSpringActuatorAutoConfiguration {

    @VisibleForTesting
    static final MediaType ACTUATOR_MEDIA_TYPE = MediaType.parse(ActuatorMediaType.V3_JSON);

    private static final List<String> MEDIA_TYPES =
            ImmutableList.of(ActuatorMediaType.V3_JSON, MediaTypeNames.JSON);

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
    @ConditionalOnMissingBean // In case HealthEndpointAutoConfiguration is excluded
    HealthStatusHttpMapper healthStatusHttpMapper() {
        return new HealthStatusHttpMapper();
    }

    @Bean
    ArmeriaServerConfigurator actuatorServerConfigurator(
            WebEndpointsSupplier endpointsSupplier,
            EndpointMediaTypes mediaTypes,
            WebEndpointProperties properties,
            HealthStatusHttpMapper healthMapper,
            CorsEndpointProperties corsProperties) {
        final EndpointMapping endpointMapping = new EndpointMapping(properties.getBasePath());

        final Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
        return sb -> {
            final CorsServiceBuilder cors;
            if (!corsProperties.getAllowedOrigins().isEmpty()) {
                cors = CorsService.builder(corsProperties.getAllowedOrigins());

                if (!corsProperties.getAllowedMethods().contains("*")) {
                    if (corsProperties.getAllowedMethods().isEmpty()) {
                        cors.allowRequestMethods(HttpMethod.GET);
                    } else {
                        cors.allowRequestMethods(
                                corsProperties.getAllowedMethods().stream().map(HttpMethod::valueOf)
                                        ::iterator);
                    }
                }

                if (!corsProperties.getAllowedHeaders().isEmpty() &&
                    !corsProperties.getAllowedHeaders().contains("*")) {
                    cors.allowRequestHeaders(corsProperties.getAllowedHeaders());
                }

                if (!corsProperties.getExposedHeaders().isEmpty()) {
                    cors.exposeHeaders(corsProperties.getExposedHeaders());
                }

                if (Boolean.TRUE.equals(corsProperties.getAllowCredentials())) {
                    cors.allowCredentials();
                }

                cors.maxAge(corsProperties.getMaxAge());
            } else {
                cors = null;
            }

            endpoints.stream()
                     .flatMap(endpoint -> endpoint.getOperations().stream())
                     .forEach(operation -> {
                         final WebOperationRequestPredicate predicate = operation.getRequestPredicate();
                         final String path = endpointMapping.createSubPath(predicate.getPath());
                         final Route route = route(predicate.getHttpMethod().name(),
                                                   path,
                                                   predicate.getConsumes(),
                                                   predicate.getProduces());
                         sb.service(route, new WebOperationService(operation, healthMapper));
                         if (cors != null) {
                             cors.route(path);
                         }
                     });

            if (StringUtils.hasText(endpointMapping.getPath())) {
                final Route route = route(
                        HttpMethod.GET.name(),
                        endpointMapping.getPath(),
                        ImmutableList.of(),
                        mediaTypes.getProduced()
                );
                final HttpService linksService = (ctx, req) -> {
                    final Map<String, Link> links =
                            new EndpointLinksResolver(endpoints).resolveLinks(req.path());
                    return HttpResponse.of(
                            HttpStatus.OK,
                            ACTUATOR_MEDIA_TYPE,
                            OBJECT_MAPPER.writeValueAsBytes(ImmutableMap.of("_links", links))
                    );
                };
                sb.service(route, linksService);
                if (cors != null) {
                    cors.route(endpointMapping.getPath());
                }
            }
            if (cors != null) {
                sb.routeDecorator().pathPrefix("/").build(cors.newDecorator());
            }
        };
    }

    private static Route route(
            String method, String path, Collection<String> consumes, Collection<String> produces) {
        return Route.builder()
                    .path(path)
                    .methods(ImmutableSet.of(HttpMethod.valueOf(method)))
                    .consumes(convertMediaTypes(consumes))
                    .produces(convertMediaTypes(produces))
                    .build();
    }

    private static Set<MediaType> convertMediaTypes(Iterable<String> mediaTypes) {
        return Streams.stream(mediaTypes).map(MediaType::parse).collect(toImmutableSet());
    }
}
