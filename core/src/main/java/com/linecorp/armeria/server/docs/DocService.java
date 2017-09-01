/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.server.composition.CompositeServiceEntry.ofCatchAll;
import static com.linecorp.armeria.server.composition.CompositeServiceEntry.ofExact;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.composition.AbstractCompositeService;
import com.linecorp.armeria.server.file.HttpFileService;
import com.linecorp.armeria.server.file.HttpVfs;

/**
 * An {@link HttpService} that provides information about the {@link Service}s running in a
 * {@link Server}. It does not require any configuration besides adding it to a {@link VirtualHost}; it
 * discovers all the eligible {@link Service}s automatically.
 *
 * <h3>How is the documentation generated?</h3>
 *
 * <p>{@link DocService} looks up the {@link DocServicePlugin}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link DocServicePlugin} implementations will
 * generate {@link ServiceSpecification}s for the {@link Service}s they support.
 */
public class DocService extends AbstractCompositeService<HttpRequest, HttpResponse> {

    private static final ObjectMapper mapper = new ObjectMapper();

    static final List<DocServicePlugin> plugins = Streams.stream(ServiceLoader.load(
            DocServicePlugin.class, DocService.class.getClassLoader())).collect(toImmutableList());

    private final Map<String, ListMultimap<String, HttpHeaders>> exampleHttpHeaders;
    private final Map<String, ListMultimap<String, String>> exampleRequests;

    private Server server;

    /**
     * Creates a new instance.
     */
    public DocService() {
        this(ImmutableMap.of(), ImmutableMap.of());
    }

    /**
     * Creates a new instance with example HTTP headers and example requests.
     */
    DocService(Map<String, ListMultimap<String, HttpHeaders>> exampleHttpHeaders,
               Map<String, ListMultimap<String, String>> exampleRequests) {

        super(ofExact("/specification.json", HttpFileService.forVfs(new DocServiceVfs())),
              ofCatchAll(HttpFileService.forClassPath(DocService.class.getClassLoader(),
                                                      "com/linecorp/armeria/server/docs")));
        this.exampleHttpHeaders = immutableCopyOf(exampleHttpHeaders, "exampleHttpHeaders");
        this.exampleRequests = immutableCopyOf(exampleRequests, "exampleRequests");
    }

    private static <T> Map<String, ListMultimap<String, T>> immutableCopyOf(
            Map<String, ListMultimap<String, T>> map, String name) {
        requireNonNull(map, name);

        return map.entrySet().stream().collect(toImmutableMap(
                Entry::getKey, e -> ImmutableListMultimap.copyOf(e.getValue())));
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

                ServiceSpecification spec = generate(services);
                spec = addDocStrings(spec, services);
                spec = addExamples(spec);

                vfs().setSpecification(mapper.writerWithDefaultPrettyPrinter()
                                             .writeValueAsBytes(spec));
            }
        });
    }

    private static ServiceSpecification generate(List<ServiceConfig> services) {
        return ServiceSpecification.merge(
                plugins.stream()
                       .map(plugin -> plugin.generateSpecification(
                               findSupportedServices(plugin, services)))
                       .collect(toImmutableList()));
    }

    private static ServiceSpecification addDocStrings(ServiceSpecification spec, List<ServiceConfig> services) {
        final Map<String, String> docStrings =
                plugins.stream()
                       .flatMap(plugin -> plugin.loadDocStrings(findSupportedServices(plugin, services))
                                                .entrySet().stream())
                       .collect(toImmutableMap(Entry::getKey, Entry::getValue, (a, b) -> a));

        return new ServiceSpecification(
                spec.services().stream()
                    .map(service -> addServiceDocStrings(service, docStrings))
                    .collect(toImmutableList()),
                spec.enums().stream()
                    .map(e -> addEnumDocStrings(e, docStrings))
                    .collect(toImmutableList()),
                spec.structs().stream()
                    .map(s -> addStructDocStrings(s, docStrings))
                    .collect(toImmutableList()),
                spec.exceptions().stream()
                    .map(e -> addExceptionDocStrings(e, docStrings))
                    .collect(toImmutableList()),
                spec.exampleHttpHeaders());
    }

    private static ServiceInfo addServiceDocStrings(ServiceInfo service, Map<String, String> docStrings) {
        return new ServiceInfo(
                service.name(),
                service.methods().stream()
                       .map(method -> addMethodDocStrings(service, method, docStrings))
                       .collect(toImmutableList()),
                service.exampleHttpHeaders(),
                docString(service.name(), service.docString(), docStrings));
    }

    private static MethodInfo addMethodDocStrings(ServiceInfo service, MethodInfo method,
                                                  Map<String, String> docStrings) {
        return new MethodInfo(method.name(),
                              method.returnTypeSignature(),
                              method.parameters().stream()
                                    .map(field -> addParameterDocString(service, method, field, docStrings))
                                    .collect(toImmutableList()),
                              method.exceptionTypeSignatures(),
                              method.endpoints(),
                              method.exampleHttpHeaders(),
                              method.exampleRequests(),
                              docString(service.name() + '/' + method.name(), method.docString(), docStrings));
    }

    private static FieldInfo addParameterDocString(ServiceInfo service, MethodInfo method, FieldInfo field,
                                                   Map<String, String> docStrings) {
        return new FieldInfo(field.name(),
                             field.requirement(),
                             field.typeSignature(),
                             docString(service.name() + '/' + method.name() + '/' + field.name(),
                                       field.docString(), docStrings));
    }

    private static EnumInfo addEnumDocStrings(EnumInfo e, Map<String, String> docStrings) {
        return new EnumInfo(e.name(),
                            e.values().stream()
                             .map(v -> addEnumValueDocString(e, v, docStrings))
                             .collect(toImmutableList()),
                            docString(e.name(), e.docString(), docStrings));
    }

    private static EnumValueInfo addEnumValueDocString(EnumInfo e, EnumValueInfo v,
                                                       Map<String, String> docStrings) {
        return new EnumValueInfo(v.name(),
                                 docString(e.name() + '/' + v.name(), v.docString(), docStrings));
    }

    private static StructInfo addStructDocStrings(StructInfo struct, Map<String, String> docStrings) {
        return new StructInfo(struct.name(),
                              struct.fields().stream()
                                    .map(field -> addFieldDocString(struct, field, docStrings))
                                    .collect(toImmutableList()),
                              docString(struct.name(), struct.docString(), docStrings));
    }

    private static ExceptionInfo addExceptionDocStrings(ExceptionInfo e, Map<String, String> docStrings) {
        return new ExceptionInfo(e.name(),
                                 e.fields().stream()
                                  .map(field -> addFieldDocString(e, field, docStrings))
                                  .collect(toImmutableList()),
                                 docString(e.name(), e.docString(), docStrings));
    }

    private static FieldInfo addFieldDocString(NamedTypeInfo parent, FieldInfo field,
                                               Map<String, String> docStrings) {
        return new FieldInfo(field.name(),
                             field.requirement(),
                             field.typeSignature(),
                             docString(parent.name() + '/' + field.name(), field.docString(), docStrings));
    }

    private static String docString(
            String key, @Nullable String currentDocString, Map<String, String> docStrings) {
        return currentDocString != null ? currentDocString : docStrings.get(key);
    }

    private ServiceSpecification addExamples(ServiceSpecification spec) {
        return new ServiceSpecification(
                spec.services().stream()
                    .map(this::addServiceExamples)
                    .collect(toImmutableList()),
                spec.enums(), spec.structs(), spec.exceptions(),
                Iterables.concat(spec.exampleHttpHeaders(),
                                 exampleHttpHeaders.getOrDefault("", ImmutableListMultimap.of()).get("")));
    }

    private ServiceInfo addServiceExamples(ServiceInfo service) {
        final ListMultimap<String, HttpHeaders> exampleHttpHeaders =
                this.exampleHttpHeaders.getOrDefault(service.name(), ImmutableListMultimap.of());
        final ListMultimap<String, String> exampleRequests =
                this.exampleRequests.getOrDefault(service.name(), ImmutableListMultimap.of());

        // Reconstruct ServiceInfo with the examples.
        return new ServiceInfo(
                service.name(),
                // Reconstruct MethodInfos with the examples.
                service.methods().stream().map(m -> new MethodInfo(
                        m.name(), m.returnTypeSignature(), m.parameters(), m.exceptionTypeSignatures(),
                        m.endpoints(), Iterables.concat(m.exampleHttpHeaders(),
                                                        exampleHttpHeaders.get(m.name())),
                        Iterables.concat(m.exampleRequests(),
                                         exampleRequests.get(m.name())),
                        m.docString()))::iterator,
                Iterables.concat(service.exampleHttpHeaders(),
                                 exampleHttpHeaders.get("")),
                service.docString());
    }

    DocServiceVfs vfs() {
        return (DocServiceVfs) ((HttpFileService) serviceAt(0)).config().vfs();
    }

    private static Set<ServiceConfig> findSupportedServices(
            DocServicePlugin plugin, List<ServiceConfig> services) {
        final Set<Class<? extends Service<?, ?>>> supportedServiceTypes = plugin.supportedServiceTypes();
        return services.stream()
                       .filter(serviceCfg -> isSupported(serviceCfg, supportedServiceTypes))
                       .collect(toImmutableSet());
    }

    private static boolean isSupported(
            ServiceConfig serviceCfg, Set<Class<? extends Service<?, ?>>> supportedServiceTypes) {
        return supportedServiceTypes.stream().anyMatch(type -> serviceCfg.service().as(type).isPresent());
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
