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

import static com.linecorp.armeria.server.composition.CompositeServiceEntry.ofCatchAll;
import static com.linecorp.armeria.server.composition.CompositeServiceEntry.ofExact;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.apache.thrift.TBase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.composition.AbstractCompositeService;
import com.linecorp.armeria.server.http.HttpService;
import com.linecorp.armeria.server.http.file.HttpFileService;
import com.linecorp.armeria.server.http.file.HttpVfs;
import com.linecorp.armeria.server.thrift.THttpService;

/**
 * An {@link HttpService} that provides information about the {@link THttpService}s running in a
 * {@link Server}. It does not require any configuration besides adding it to a {@link VirtualHost}; it
 * discovers all {@link THttpService}s in the {@link Server} automatically.
 */
public class DocService extends AbstractCompositeService<HttpRequest, HttpResponse> {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<Class<?>, ? extends TBase<?, ?>> sampleRequests;
    private final Map<Class<?>, Map<String, String>> sampleHttpHeaders;

    private Server server;

    /**
     * Creates a new instance, pre-populating debug forms with the provided {@code sampleRequests}.
     * {@code sampleRequests} should be a list of Thrift argument objects for methods that should be
     * pre-populated (e.g., a populated hello_args object for the hello method on HelloService).
     */
    @SafeVarargs
    public <T extends TBase<?, ?>> DocService(T... sampleRequests) {
        this(Arrays.asList(requireNonNull(sampleRequests, "sampleRequests")));
    }

    /**
     * Creates a new instance, pre-populating debug forms with the provided {@code sampleRequests}.
     * {@code sampleRequests} should be a list of Thrift argument objects for methods that should be
     * pre-populated (e.g., a populated hello_args object for the hello method on HelloService).
     */
    public DocService(Iterable<? extends TBase<?, ?>> sampleRequests) {
        this(sampleRequests, Collections.emptyMap());
    }

    /**
     * Creates a new instance, prepopulating debug forms with the provided {@code sampleRequests}
     * and {@code sampleHttpHeaders}.
     *
     * {@code sampleRequests} should be a list of Thrift argument objects for methods that should be
     * prepopulated (e.g., a populated hello_args object for the hello method on HelloService).
     * {@code sampleHttpHeaders} should be a map of Thrift Service class to String-String map. The Thrift
     * Service class is like HelloService.class, and String-String map is HTTP Header name to value map.
     */
    public DocService(Iterable<? extends TBase<?, ?>> sampleRequests,
                      Map<Class<?>, Map<String, String>> sampleHttpHeaders) {
        super(ofExact("/specification.json", HttpFileService.forVfs(new DocServiceVfs())),
              ofCatchAll(HttpFileService.forClassPath(DocService.class.getClassLoader(),
                                                      "com/linecorp/armeria/server/docs")));
        requireNonNull(sampleRequests, "sampleRequests");
        this.sampleRequests = StreamSupport.stream(sampleRequests.spliterator(), false)
                                           .collect(Collectors.toMap(Object::getClass, Function.identity()));
        this.sampleHttpHeaders = sampleHttpHeaders;
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

                final List<ServiceConfig> services = config.serviceConfigs().stream()
                                                           .filter(se -> virtualHosts.contains(se.virtualHost()))
                                                           .collect(Collectors.toList());

                vfs().setSpecification(mapper.writerWithDefaultPrettyPrinter()
                                             .writeValueAsBytes(Specification.forServiceConfigs(
                                                     services, sampleRequests, sampleHttpHeaders)));
            }
        });
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
