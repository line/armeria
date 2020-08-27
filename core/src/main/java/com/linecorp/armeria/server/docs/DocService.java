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
import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.file.AbstractHttpVfs;
import com.linecorp.armeria.server.file.AggregatedHttpFile;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.file.HttpFileBuilder;
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
 *
 * @see DocServiceBuilder#include(DocServiceFilter)
 * @see DocServiceBuilder#exclude(DocServiceFilter)
 */
public final class DocService extends SimpleDecoratingHttpService {

    private static final Logger logger = LoggerFactory.getLogger(DocService.class);

    private static final ObjectMapper jsonMapper = new ObjectMapper()
            .setSerializationInclusion(Include.NON_ABSENT);

    static final List<DocServicePlugin> plugins = ImmutableList.copyOf(ServiceLoader.load(
            DocServicePlugin.class, DocService.class.getClassLoader()));

    static {
        logger.info("Loaded {}: {}", DocServicePlugin.class.getSimpleName(), plugins);
    }

    /**
     * Returns a new {@link DocServiceBuilder}.
     */
    public static DocServiceBuilder builder() {
        return new DocServiceBuilder();
    }

    private final Map<String, ListMultimap<String, HttpHeaders>> exampleHeaders;
    private final Map<String, ListMultimap<String, String>> exampleRequests;
    private final Map<String, ListMultimap<String, String>> examplePaths;
    private final Map<String, ListMultimap<String, String>> exampleQueries;
    private final List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers;
    private final DocServiceFilter filter;

    @Nullable
    private Server server;

    /**
     * Creates a new instance.
     */
    public DocService() {
        this(/* exampleHeaders */ ImmutableMap.of(), /* exampleRequests */ ImmutableMap.of(),
             /* examplePaths */ ImmutableMap.of(), /* exampleQueries */ ImmutableMap.of(),
             /* injectedScriptSuppliers */ ImmutableList.of(), DocServiceBuilder.ALL_SERVICES);
    }

    /**
     * Creates a new instance with example HTTP headers and example requests and injected scripts.
     */
    DocService(Map<String, ListMultimap<String, HttpHeaders>> exampleHeaders,
               Map<String, ListMultimap<String, String>> exampleRequests,
               Map<String, ListMultimap<String, String>> examplePaths,
               Map<String, ListMultimap<String, String>> exampleQueries,
               List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers,
               DocServiceFilter filter) {

        super(FileService.of(new DocServiceVfs()));

        this.exampleHeaders = immutableCopyOf(exampleHeaders, "exampleHeaders");
        this.exampleRequests = immutableCopyOf(exampleRequests, "exampleRequests");
        this.examplePaths = immutableCopyOf(examplePaths, "examplePaths");
        this.exampleQueries = immutableCopyOf(exampleQueries, "exampleQueries");
        this.injectedScriptSuppliers = requireNonNull(injectedScriptSuppliers, "injectedScriptSuppliers");
        this.filter = requireNonNull(filter, "filter");
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

                ServiceSpecification spec = generate(services, filter);

                spec = addDocStrings(spec, services);
                spec = addExamples(spec);

                final List<Version> versions = ImmutableList.copyOf(
                        Version.getAll(DocService.class.getClassLoader()).values());

                vfs().put("/specification.json",
                          jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(spec));
                vfs().put("/versions.json",
                          jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(versions));
            }
        });
    }

    private static ServiceSpecification generate(List<ServiceConfig> services, DocServiceFilter filter) {
        return ServiceSpecification.merge(
                plugins.stream()
                       .map(plugin -> plugin.generateSpecification(
                               findSupportedServices(plugin, services), filter))
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
                spec.exampleHeaders());
    }

    private static ServiceInfo addServiceDocStrings(ServiceInfo service, Map<String, String> docStrings) {
        return new ServiceInfo(
                service.name(),
                service.methods().stream()
                       .map(method -> addMethodDocStrings(service, method, docStrings))
                       .collect(toImmutableList()),
                service.exampleHeaders(),
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
                              method.exampleHeaders(),
                              method.exampleRequests(),
                              method.examplePaths(),
                              method.exampleQueries(),
                              method.httpMethod(),
                              docString(service.name() + '/' + method.name(), method.docString(), docStrings));
    }

    private static FieldInfo addParameterDocString(ServiceInfo service, MethodInfo method, FieldInfo field,
                                                   Map<String, String> docStrings) {
        return new FieldInfo(field.name(),
                             field.location(),
                             field.requirement(),
                             field.typeSignature(),
                             field.childFieldInfos(),
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
                                 v.intValue(),
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
                             field.location(),
                             field.requirement(),
                             field.typeSignature(),
                             field.childFieldInfos(),
                             docString(parent.name() + '/' + field.name(), field.docString(), docStrings));
    }

    @Nullable
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
                Iterables.concat(spec.exampleHeaders(),
                                 exampleHeaders.getOrDefault("", ImmutableListMultimap.of()).get("")));
    }

    private ServiceInfo addServiceExamples(ServiceInfo service) {
        final ListMultimap<String, HttpHeaders> exampleHeaders =
                this.exampleHeaders.getOrDefault(service.name(), ImmutableListMultimap.of());
        final ListMultimap<String, String> exampleRequests =
                this.exampleRequests.getOrDefault(service.name(), ImmutableListMultimap.of());
        final ListMultimap<String, String> examplePaths =
                this.examplePaths.getOrDefault(service.name(), ImmutableListMultimap.of());
        final ListMultimap<String, String> exampleQueries =
                this.exampleQueries.getOrDefault(service.name(), ImmutableListMultimap.of());

        // Reconstruct ServiceInfo with the examples.
        return new ServiceInfo(
                service.name(),
                // Reconstruct MethodInfos with the examples.
                service.methods().stream().map(m -> new MethodInfo(
                        m.name(), m.returnTypeSignature(), m.parameters(), m.exceptionTypeSignatures(),
                        m.endpoints(),
                        // Show the examples added via `DocServiceBuilder` before the examples
                        // generated by the plugin.
                        concatAndDedup(exampleHeaders.get(m.name()), m.exampleHeaders()),
                        concatAndDedup(exampleRequests.get(m.name()), m.exampleRequests()),
                        concatAndDedup(examplePaths.get(m.name()), m.examplePaths()),
                        concatAndDedup(exampleQueries.get(m.name()), m.exampleQueries()),
                        m.httpMethod(), m.docString()))::iterator,
                Iterables.concat(service.exampleHeaders(), exampleHeaders.get("")),
                service.docString());
    }

    private static <T> Iterable<T> concatAndDedup(Iterable<T> first, Iterable<T> second) {
        return Stream.concat(Streams.stream(first), Streams.stream(second)).distinct()
                     .collect(toImmutableList());
    }

    private DocServiceVfs vfs() {
        return (DocServiceVfs) ((FileService) unwrap()).config().vfs();
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
        return supportedServiceTypes.stream().anyMatch(type -> serviceCfg.service().as(type) != null);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if ("/injected.js".equals(ctx.mappedPath())) {
            return HttpResponse.of(MediaType.JAVASCRIPT_UTF_8,
                                   injectedScriptSuppliers.stream()
                                                          .map(f -> f.apply(ctx, req))
                                                          .collect(Collectors.joining("\n")));
        }

        return unwrap().serve(ctx, req);
    }

    static final class DocServiceVfs extends AbstractHttpVfs {

        private final HttpVfs staticFiles = HttpVfs.of(DocService.class.getClassLoader(),
                                                       "com/linecorp/armeria/server/docs");

        private final Map<String, AggregatedHttpFile> files = new ConcurrentHashMap<>();

        @Override
        public HttpFile get(
                Executor fileReadExecutor, String path, Clock clock,
                @Nullable String contentEncoding, HttpHeaders additionalHeaders) {

            final AggregatedHttpFile file = files.get(path);
            if (file != null) {
                assert file != AggregatedHttpFile.nonExistent();

                final HttpFileBuilder builder = HttpFile.builder(file.content(),
                                                                 file.attributes().lastModifiedMillis());
                builder.autoDetectedContentType(false);
                builder.clock(clock);
                builder.setHeaders(file.headers());
                builder.setHeaders(additionalHeaders);
                if (contentEncoding != null) {
                    builder.setHeader(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
                }
                return builder.build();
            }

            return staticFiles.get(fileReadExecutor, path, clock, contentEncoding, additionalHeaders);
        }

        @Override
        public String meterTag() {
            return DocService.class.getSimpleName();
        }

        void put(String path, byte[] content) {
            put(path, content, MediaType.JSON_UTF_8);
        }

        private void put(String path, byte[] content, MediaType mediaType) {
            files.put(path, AggregatedHttpFile.builder(HttpData.wrap(content))
                                              .contentType(mediaType)
                                              .cacheControl(ServerCacheControl.REVALIDATED)
                                              .build());
        }
    }
}
