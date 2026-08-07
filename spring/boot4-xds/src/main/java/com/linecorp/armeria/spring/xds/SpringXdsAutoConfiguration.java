/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.spring.xds;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsExtensionFactory;
import com.linecorp.armeria.xds.XdsResourceReader;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * Spring Boot auto-configuration that sets up xDS resource loading from
 * Spring {@link Environment} properties and automatically refreshes on
 * {@link EnvironmentChangeEvent}.
 *
 * <p><b>Note:</b> YAML configuration files ({@code application.yml}) are recommended
 * over {@code .properties} files because xDS resource values are multi-line YAML
 * that cannot be represented correctly in {@code .properties} format.
 *
 * <h2>xDS resources</h2>
 *
 * <p>Each xDS resource is stored as a separate property with the key
 * {@code armeria.xds.<type>.<resource-name>} containing the resource YAML
 * (without {@code @type} wrappers — the type is inferred from the
 * subscription context). The default prefixes are:
 * <ul>
 *   <li>{@code armeria.xds.listener.} — for Listener resources (LDS)</li>
 *   <li>{@code armeria.xds.cluster.} — for Cluster resources (CDS)</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * armeria:
 *   xds:
 *     listener:
 *       my-listener: |
 *         name: my-listener
 *         api_listener: ...
 *     cluster:
 *       my-cluster: |
 *         name: my-cluster
 *         type: STATIC
 *         load_assignment:
 *           cluster_name: my-cluster
 *           endpoints:
 *             - lb_endpoints:
 *                 - endpoint:
 *                     address:
 *                       socket_address:
 *                         address: 127.0.0.1
 *                         port_value: 8080
 * }</pre>
 *
 * <h2>Bootstrap</h2>
 *
 * <p>The xDS {@link Bootstrap} is created from the {@code armeria.xds.bootstrap} property.
 * If not set, a default bootstrap is loaded from
 * {@code META-INF/armeria/xds/default-bootstrap.yml}, which configures LDS with
 * prefix {@code armeria.xds.listener.} and CDS with prefix {@code armeria.xds.cluster.}
 * via {@link SpringConfigSourceFactory}.
 *
 * <p>To customize the bootstrap, set {@code armeria.xds.bootstrap} in your
 * {@code application.yml}:
 * <pre>{@code
 * armeria:
 *   xds:
 *     bootstrap: |
 *       dynamic_resources:
 *         cds_config:
 *           custom_config_source:
 *             name: armeria.config_source.spring
 *             typed_config:
 *               "@type": type.googleapis.com/armeria.xds.spring.SpringConfigSource
 *               prefix: "my.custom.prefix."
 * }</pre>
 */
@UnstableApi
@AutoConfiguration
@ConditionalOnClass(XdsBootstrap.class)
@PropertySource(value = "classpath:META-INF/armeria/xds/default-bootstrap.yml",
                factory = YamlFilePropertySourceFactory.class)
public class SpringXdsAutoConfiguration {

    /**
     * The property key for the xDS bootstrap YAML.
     */
    static final String BOOTSTRAP_PROPERTY = "armeria.xds.bootstrap";

    @Bean
    @ConditionalOnMissingBean
    SpringConfigSourceFactory springConfigSourceFactory(Environment environment) {
        return SpringConfigSourceFactory.of(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    XdsBootstrap xdsBootstrap(Environment environment, List<XdsExtensionFactory> extensionFactories) {
        final String bootstrapYaml = environment.getRequiredProperty(BOOTSTRAP_PROPERTY);
        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml, Bootstrap.class);
        return XdsBootstrap.builder(bootstrap)
                           .extensionFactories(extensionFactories)
                           .build();
    }

    @Bean
    ApplicationListener<EnvironmentChangeEvent> springXdsRefreshListener(SpringConfigSourceFactory factory) {
        return event -> factory.refresh();
    }
}
