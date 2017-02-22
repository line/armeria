/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.server.composition.CompositeServiceEntry.ofCatchAll;
import static com.linecorp.armeria.server.composition.CompositeServiceEntry.ofExact;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.composition.AbstractCompositeService;
import com.linecorp.armeria.server.http.HttpService;
import com.linecorp.armeria.server.http.file.HttpFileService;
import com.linecorp.armeria.server.http.file.HttpVfs;

/**
 * An {@link HttpService} that provides information about the {@link Service}s running in a
 * {@link Server}. It does not require any configuration besides adding it to a {@link VirtualHost}; it
 * discovers all the eligible {@link Service}s automatically.
 *
 * <h3>How is the documentation generated?</h3>
 *
 * <p>{@link DocService} looks up the {@link ServiceSpecificationGenerator}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link ServiceSpecificationGenerator} implementations will
 * generate {@link ServiceSpecification} for the {@link Service}s it supports.
 */
public class DocService extends AbstractCompositeService<HttpRequest, HttpResponse> {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ListMultimap<String, HttpHeaders> exampleHeaders;
    private Server server;

    /**
     * Creates a new instance.
     */
    public DocService() {
        this(ImmutableMap.of());
    }

    /**
     * Creates a new instance with example HTTP headers.
     *
     * @param exampleHeaders a {@link Map} of example {@link HttpHeaders} whose key is the full name of
     *                       a service. Use the empty string to add the example HTTP headers
     *                       to all services.
     */
    public DocService(Map<String, ? extends Iterable<HttpHeaders>> exampleHeaders) {
        super(ofExact("/specification.json", HttpFileService.forVfs(new DocServiceVfs())),
              ofCatchAll(HttpFileService.forClassPath(DocService.class.getClassLoader(),
                                                      "com/linecorp/armeria/server/docs")));

        requireNonNull(exampleHeaders, "exampleHeaders");
        final ImmutableListMultimap.Builder<String, HttpHeaders> builder = ImmutableListMultimap.builder();
        for (Entry<String, ? extends Iterable<HttpHeaders>> e : exampleHeaders.entrySet()) {
            final String k = e.getKey();
            for (HttpHeaders v : e.getValue()) {
                builder.put(k, HttpHeaders.copyOf(v).asImmutable());
            }
        }
        this.exampleHeaders = builder.build();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);

        if (server != null) {
            if (server != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        server = cfg.server();

        // Build the Specification after all the services are added to the server.
        server.addListener(new ServerListenerAdapter() {
            @Override
            public void serverStarting(Server server) throws Exception {
                final ServerConfig config = server.config();
                final List<VirtualHost> virtualHosts = config.findVirtualHosts(DocService.this);

                final List<ServiceConfig> services =
                        config.serviceConfigs().stream()
                              .filter(se -> virtualHosts.contains(se.virtualHost()))
                              .collect(toImmutableList());

                final ServiceLoader<ServiceSpecificationGenerator> loader = ServiceLoader.load(
                        ServiceSpecificationGenerator.class, DocService.class.getClassLoader());
                final ServiceSpecification spec = ServiceSpecification.merge(
                        Streams.stream(loader)
                               .map(gen -> generate(gen, services))
                               .collect(Collectors.toList()));

                vfs().setSpecification(mapper.writerWithDefaultPrettyPrinter()
                                             .writeValueAsBytes(spec));
            }
        });
    }

    private ServiceSpecification generate(ServiceSpecificationGenerator gen, List<ServiceConfig> services) {
        final Set<ServiceConfig> supportedServices = findSupportedServices(gen, services);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Map<String, List<HttpHeaders>> exampleHeaders =
                (Map<String, List<HttpHeaders>>) (Map) this.exampleHeaders.asMap();
        return gen.generate(supportedServices, exampleHeaders);
    }

    private static Set<ServiceConfig> findSupportedServices(
            ServiceSpecificationGenerator generator, List<ServiceConfig> services) {
        final Set<Class<? extends Service<?, ?>>> supportedServiceTypes = generator.supportedServiceTypes();
        return services.stream()
                       .filter(serviceCfg -> isSupported(serviceCfg, supportedServiceTypes))
                       .collect(toImmutableSet());
    }

    private static boolean isSupported(
            ServiceConfig serviceCfg, Set<Class<? extends Service<?, ?>>> supportedServiceTypes) {
        return supportedServiceTypes.stream().anyMatch(type -> serviceCfg.service().as(type).isPresent());
    }

    DocServiceVfs vfs() {
        return (DocServiceVfs) ((HttpFileService) serviceAt(0)).config().vfs();
    }

    static final class DocServiceVfs implements HttpVfs {

        private volatile Entry entry = Entry.NONE;

        @Override
        public Entry get(String path, @Nullable String contentEncoding) {
            return entry;
        }

        void setSpecification(byte[] content) {
            assert entry == Entry.NONE;
            entry = new ByteArrayEntry("/", MediaType.JSON_UTF_8, content);
        }
    }
}
