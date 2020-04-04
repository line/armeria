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

package com.linecorp.armeria.internal.spring;

import static java.util.stream.Collectors.toList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaServerInitializedEvent;

/**
 * {@link ApplicationContextInitializer} that sets {@link Environment} properties for the
 * ports that {@link Server} servers are actually listening on. The properties
 * {@literal "local.armeria.port"}, {@literal "local.armeria.ports"} can be injected directly into tests using
 * {@link Value @Value} or obtained via the {@link Environment}.
 * Properties are automatically propagated up to any parent context.
 */
public class ArmeriaServerPortInfoApplicationContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applicationContext.addApplicationListener(new Listener(applicationContext));
    }

    private static class Listener implements ApplicationListener<ArmeriaServerInitializedEvent> {

        private static final String LOCAL_ARMERIA_PORT = "local.armeria.port";
        private static final String LOCAL_ARMERIA_PORTS = "local.armeria.ports";

        private final ConfigurableApplicationContext applicationContext;

        Listener(ConfigurableApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        public void onApplicationEvent(ArmeriaServerInitializedEvent event) {
            setPortProperty(applicationContext, event.getServer().activeLocalPort());
            setPortProperty(applicationContext, event.getServer().activePorts().values().stream()
                                                     .map(p -> p.localAddress().getPort())
                                                     .collect(toList()));
        }

        private void setPortProperty(ApplicationContext context, int port) {
            if (context instanceof ConfigurableApplicationContext) {
                setPortProperty(((ConfigurableApplicationContext) context).getEnvironment(), port);
            }
            if (context.getParent() != null) {
                setPortProperty(context.getParent(), port);
            }
        }

        private void setPortProperty(ConfigurableEnvironment environment, int port) {
            final MutablePropertySources sources = environment.getPropertySources();
            PropertySource<?> source = sources.get("server.ports");
            if (source == null) {
                source = new MapPropertySource("server.ports", new HashMap<>());
                sources.addFirst(source);
            }
            setPortProperty(port, source);
        }

        @SuppressWarnings("unchecked")
        private void setPortProperty(int port, PropertySource<?> source) {
            ((Map<String, Object>) source.getSource()).put(LOCAL_ARMERIA_PORT, port);
        }

        private void setPortProperty(ApplicationContext context, List<Integer> ports) {
            if (context instanceof ConfigurableApplicationContext) {
                setPortProperty(((ConfigurableApplicationContext) context).getEnvironment(), ports);
            }
            if (context.getParent() != null) {
                setPortProperty(context.getParent(), ports);
            }
        }

        private void setPortProperty(ConfigurableEnvironment environment, List<Integer> ports) {
            final MutablePropertySources sources = environment.getPropertySources();
            PropertySource<?> source = sources.get("server.ports");
            if (source == null) {
                source = new MapPropertySource("server.ports", new HashMap<>());
                sources.addFirst(source);
            }
            setPortProperty(ports, source);
        }

        @SuppressWarnings("unchecked")
        private void setPortProperty(List<Integer> ports, PropertySource<?> source) {
            ((Map<String, Object>) source.getSource()).put(LOCAL_ARMERIA_PORTS, ports);
        }
    }
}
