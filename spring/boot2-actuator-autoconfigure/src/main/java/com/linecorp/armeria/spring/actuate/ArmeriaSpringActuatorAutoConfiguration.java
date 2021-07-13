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
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.configurePorts;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.SocketUtils;
import org.springframework.util.StringUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.DecoratingServiceBindingBuilder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

/**
 * A {@link Configuration} to enable actuator endpoints on an Armeria server. Corresponds to
 * {@link org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration}.
 */
@Configuration
@AutoConfigureAfter(EndpointAutoConfiguration.class)
@EnableConfigurationProperties({
        WebEndpointProperties.class, CorsEndpointProperties.class, ManagementServerProperties.class,
        ArmeriaSettings.class
})
public class ArmeriaSpringActuatorAutoConfiguration {

    @VisibleForTesting
    static final MediaType ACTUATOR_MEDIA_TYPE = MediaType.parse(ActuatorMediaType.V3_JSON);

    private static final List<String> MEDIA_TYPES =
            ImmutableList.of(ActuatorMediaType.V3_JSON, MediaTypeNames.JSON);

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
    SimpleHttpCodeStatusMapper simpleHttpCodeStatusMapper() {
        return new SimpleHttpCodeStatusMapper();
    }

    @Bean
    ArmeriaServerConfigurator actuatorServerConfigurator(
            WebEndpointsSupplier endpointsSupplier,
            EndpointMediaTypes mediaTypes,
            WebEndpointProperties properties,
            SimpleHttpCodeStatusMapper statusMapper,
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
                         sb.service(route, new WebOperationService(operation, statusMapper));
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
                    return HttpResponse.ofJson(ACTUATOR_MEDIA_TYPE, ImmutableMap.of("_links", links));
                };
                sb.route().addRoute(route).defaultServiceName("LinksService").build(linksService);
                if (cors != null) {
                    cors.route(endpointMapping.getPath());
                }
            }
            if (cors != null) {
                sb.routeDecorator().pathPrefix("/").build(cors.newDecorator());
            }
        };
    }

    @Bean
    @ConditionalOnProperty("management.server.port")
    ArmeriaServerConfigurator secureActuatorServerConfigurator(WebEndpointProperties properties,
                                                               ManagementServerProperties serverProperties,
                                                               ConfigurableEnvironment environment,
                                                               ArmeriaSettings armeriaSettings) {
        return sb -> {
            final Port port = obtainManagementServerPort(serverProperties.getPort());
            if (port != null) {
                configurePorts(sb, ImmutableList.of(port));
                addLocalManagementPortPropertyAlias(environment, port);
                configureSecureDecorator(sb, port, properties.getBasePath(), armeriaSettings);
            }
        };
    }

    @Nullable
    private static Port obtainManagementServerPort(Integer port) {
        int actualPort = requireNonNull(port, "port");
        if (actualPort < 0) {
            return null;
        }
        if (actualPort == 0) {
            actualPort = SocketUtils.findAvailableTcpPort();
        }
        return new Port().setPort(actualPort).setProtocol(SessionProtocol.HTTP);
    }

    private static void addLocalManagementPortPropertyAlias(ConfigurableEnvironment environment, Port port) {
        environment.getPropertySources().addLast(new PropertySource<Object>("Management Server") {

            @Override
            public Object getProperty(String name) {
                if ("local.management.port".equals(name)) {
                    return port.getPort();
                }
                return null;
            }
        });
    }

    private static void configureSecureDecorator(ServerBuilder sb, Port port,
                                                 @Nullable String basePath, ArmeriaSettings armeriaSettings) {
        final DecoratingServiceBindingBuilder builder = sb.routeDecorator();
        if (armeriaSettings.isEnableMetrics() && !Strings.isNullOrEmpty(armeriaSettings.getMetricsPath())) {
            builder.path(armeriaSettings.getMetricsPath());
        }
        if (!Strings.isNullOrEmpty(armeriaSettings.getHealthCheckPath())) {
            builder.path(armeriaSettings.getHealthCheckPath());
        }
        if (!Strings.isNullOrEmpty(armeriaSettings.getDocsPath())) {
            builder.path(armeriaSettings.getDocsPath());
        }
        if (!Strings.isNullOrEmpty(basePath)) {
            builder.path(basePath)
                   .pathPrefix(basePath);
        }
        builder.build((delegate, ctx, req) -> {
            final InetSocketAddress laddr = ctx.localAddress();
            if (port.getPort() == laddr.getPort()) {
                return delegate.serve(ctx, req);
            } else {
                return HttpResponse.of(404);
            }
        });
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
