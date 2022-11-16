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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
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
import com.linecorp.armeria.server.file.MediaTypeResolver;

/**
 * An {@link HttpService} that provides information about the {@link Service}s running in a
 * {@link Server}. It does not require any configuration besides adding it to a {@link VirtualHost}; it
 * discovers all the eligible {@link Service}s automatically.
 *
 * <h2>How is the documentation generated?</h2>
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

    static final List<NamedTypeInfoProvider> spiNamedTypeInfoProviders =
            ImmutableList.copyOf(ServiceLoader.load(
                    NamedTypeInfoProvider.class, DocService.class.getClassLoader()));

    static {
        logger.debug("Available {}s: {}", DocServicePlugin.class.getSimpleName(), plugins);
        logger.debug("Available {}s: {}", NamedTypeInfoProvider.class.getSimpleName(),
                     spiNamedTypeInfoProviders);
    }

    /**
     * Returns a new {@link DocServiceBuilder}.
     */
    public static DocServiceBuilder builder() {
        return new DocServiceBuilder();
    }

    private final List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers;
    @Nullable
    private Server server;

    /**
     * Creates a new instance.
     */
    public DocService() {
        this(/* exampleHeaders */ ImmutableMap.of(), /* exampleRequests */ ImmutableMap.of(),
                /* examplePaths */ ImmutableMap.of(), /* exampleQueries */ ImmutableMap.of(),
                /* injectedScriptSuppliers */ ImmutableList.of(), DocServiceBuilder.ALL_SERVICES,
                                  null);
    }

    /**
     * Creates a new instance with example HTTP headers and example requests and injected scripts.
     */
    DocService(Map<String, ListMultimap<String, HttpHeaders>> exampleHeaders,
               Map<String, ListMultimap<String, String>> exampleRequests,
               Map<String, ListMultimap<String, String>> examplePaths,
               Map<String, ListMultimap<String, String>> exampleQueries,
               List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers,
               DocServiceFilter filter, @Nullable NamedTypeInfoProvider namedTypeInfoProvider) {
        this(new ExampleSupport(immutableCopyOf(exampleHeaders, "exampleHeaders"),
                                immutableCopyOf(exampleRequests, "exampleRequests"),
                                immutableCopyOf(examplePaths, "examplePaths"),
                                immutableCopyOf(exampleQueries, "exampleQueries")),
             injectedScriptSuppliers, filter, namedTypeInfoProvider);
    }

    private DocService(ExampleSupport exampleSupport,
                       List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers,
                       DocServiceFilter filter, @Nullable NamedTypeInfoProvider namedTypeInfoProvider) {
        this(new SpecificationLoader(exampleSupport, filter, namedTypeInfoProvider),
             injectedScriptSuppliers);
    }

    private DocService(SpecificationLoader specificationLoader,
                       List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers) {
        super(FileService.builder(new DocServiceVfs(specificationLoader))
                         .serveCompressedFiles(true)
                         .autoDecompress(true)
                         .build());
        this.injectedScriptSuppliers = requireNonNull(injectedScriptSuppliers, "injectedScriptSuppliers");
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
                final ExecutorService executorService = Executors.newSingleThreadExecutor(
                        ThreadFactories.newThreadFactory("docservice-loader", true));
                vfs().specificationLoader.updateServices(services, executorService).handle((res, e) -> {
                    executorService.shutdown();
                    return null;
                });
            }
        });
    }

    private DocServiceVfs vfs() {
        return (DocServiceVfs) ((FileService) unwrap()).config().vfs();
    }

    static Set<ServiceConfig> findSupportedServices(
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

    static final class SpecificationLoader {

        private static final String versionsPath = "/versions.json";
        private static final String specificationPath = "/specification.json";
        private static final Set<String> targetPaths = ImmutableSet.of(versionsPath, specificationPath);
        private static final CompletableFuture<AggregatedHttpFile> loadFailedFuture =
                UnmodifiableFuture.exceptionallyCompletedFuture(
                        new IllegalStateException("File load not triggered"));

        private final ExampleSupport exampleSupport;
        private final DocServiceFilter filter;
        private final NamedTypeInfoProvider namedTypeInfoProvider;
        private final Map<String, CompletableFuture<AggregatedHttpFile>> files = new ConcurrentHashMap<>();
        private List<ServiceConfig> services = Collections.emptyList();

        SpecificationLoader(
                ExampleSupport exampleSupport,
                DocServiceFilter filter,
                @Nullable NamedTypeInfoProvider namedTypeInfoProvider) {
            this.exampleSupport = exampleSupport;
            this.filter = filter;
            this.namedTypeInfoProvider = composeNamedTypeInfoProvider(namedTypeInfoProvider);
        }

        boolean contains(String path) {
            return targetPaths.contains(path);
        }

        CompletableFuture<List<AggregatedHttpFile>> updateServices(List<ServiceConfig> services,
                                                                   Executor executor) {
            this.services = services;
            final List<CompletableFuture<AggregatedHttpFile>> files = targetPaths.stream().map(
                    path -> load(path, executor)).collect(Collectors.toList());
            return CompletableFutures.allAsList(files);
        }

        CompletableFuture<AggregatedHttpFile> get(String path) {
            assert targetPaths.contains(path);
            return files.getOrDefault(path, loadFailedFuture);
        }

        private CompletableFuture<AggregatedHttpFile> load(String path, Executor executor) {
            if (versionsPath.equals(path)) {
                return loadVersions(executor);
            } else if (specificationPath.equals(path)) {
                return loadSpecifications(executor);
            } else {
                throw new Error(); // Should never reach here.
            }
        }

        private CompletableFuture<AggregatedHttpFile> loadVersions(Executor executor) {
            return files.computeIfAbsent(versionsPath, key -> CompletableFuture.supplyAsync(() -> {
                final List<Version> versions = ImmutableList.copyOf(
                        Version.getAll(DocService.class.getClassLoader()).values());
                try {
                    final byte[] content = jsonMapper.writerWithDefaultPrettyPrinter()
                                                     .writeValueAsBytes(versions);
                    return toFile(content, MediaType.JSON_UTF_8);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        private CompletableFuture<AggregatedHttpFile> loadSpecifications(Executor executor) {
            return files.computeIfAbsent(specificationPath, key -> {
                return CompletableFuture.supplyAsync(() -> {
                    final DocStringSupport docStringSupport = new DocStringSupport(services);
                    ServiceSpecification spec = generate(services);
                    spec = docStringSupport.addDocStrings(spec);
                    spec = exampleSupport.addExamples(spec);
                    try {
                        final byte[] content = jsonMapper.writerWithDefaultPrettyPrinter()
                                                         .writeValueAsBytes(spec);
                        return toFile(content, MediaType.JSON_UTF_8);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
            });
        }

        private static AggregatedHttpFile toFile(byte[] content, MediaType mediaType) {
            return AggregatedHttpFile
                    .builder(HttpData.wrap(content))
                    .contentType(mediaType)
                    .cacheControl(ServerCacheControl.REVALIDATED)
                    .build();
        }

        private ServiceSpecification generate(List<ServiceConfig> services) {
            return ServiceSpecification.merge(
                    plugins.stream()
                           .map(plugin -> plugin.generateSpecification(
                                   findSupportedServices(plugin, services), filter, namedTypeInfoProvider))
                           .collect(toImmutableList()));
        }

        private static NamedTypeInfoProvider composeNamedTypeInfoProvider(
                @Nullable NamedTypeInfoProvider namedTypeInfoProvider) {
            return typeDescriptor -> {
                if (namedTypeInfoProvider != null) {
                    // Respect user-defined provider first.
                    final NamedTypeInfo namedTypeInfo = namedTypeInfoProvider.newNamedTypeInfo(typeDescriptor);
                    if (namedTypeInfo != null) {
                        return namedTypeInfo;
                    }
                }

                for (NamedTypeInfoProvider provider : spiNamedTypeInfoProviders) {
                    final NamedTypeInfo namedTypeInfo = provider.newNamedTypeInfo(typeDescriptor);
                    if (namedTypeInfo != null) {
                        return namedTypeInfo;
                    }
                }
                return null;
            };
        }
    }

    static final class DocServiceVfs extends AbstractHttpVfs {
        private final SpecificationLoader specificationLoader;
        private final HttpVfs staticFiles = HttpVfs.of(DocService.class.getClassLoader(),
                                                       "com/linecorp/armeria/server/docs");

        DocServiceVfs(SpecificationLoader specificationLoader) {
            this.specificationLoader = specificationLoader;
        }

        @Deprecated
        @Override
        public HttpFile get(
                Executor fileReadExecutor, String path, Clock clock,
                @Nullable String contentEncoding, HttpHeaders additionalHeaders) {
            return get(fileReadExecutor, path, clock, contentEncoding, additionalHeaders,
                       MediaTypeResolver.ofDefault());
        }

        @Override
        public HttpFile get(
                Executor fileReadExecutor, String path, Clock clock,
                @Nullable String contentEncoding, HttpHeaders additionalHeaders,
                MediaTypeResolver mediaTypeResolver) {
            if (specificationLoader.contains(path)) {
                return HttpFile.from(specificationLoader.get(path).thenApply(file -> {
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
                }));
            }

            final HttpHeadersBuilder headers = additionalHeaders.toBuilder();
            headers.set(HttpHeaderNames.CACHE_CONTROL, ServerCacheControl.REVALIDATED.asHeaderValue());

            return staticFiles.get(fileReadExecutor, path, clock, contentEncoding,
                                   headers.build(), MediaTypeResolver.ofDefault());
        }

        @Override
        public String meterTag() {
            return DocService.class.getSimpleName();
        }
    }
}
