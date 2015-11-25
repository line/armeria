/*
 * Copyright 2015 LINE Corporation
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

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceEntry;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.composition.AbstractCompositeService;
import com.linecorp.armeria.server.http.HttpService;
import com.linecorp.armeria.server.http.file.HttpFileService;
import com.linecorp.armeria.server.http.file.HttpVfs;
import com.linecorp.armeria.server.thrift.ThriftService;

/**
 * An {@link HttpService} that provides information about the {@link ThriftService}s running in a
 * {@link Server}. It does not require any configuration besides adding it to a {@link VirtualHost}; it
 * discovers all {@link ThriftService}s in the {@link Server} automatically.
 */
public class DocService extends AbstractCompositeService {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a new instance.
     */
    public DocService() {
        super(ofExact("/specification.json", HttpFileService.forVfs(new DocServiceVfs())),
              ofCatchAll(HttpFileService.forClassPath(DocService.class.getClassLoader(),
                                                      "com/linecorp/armeria/server/docs")));
    }

    @Override
    public void serviceAdded(Server server) throws Exception {
        super.serviceAdded(server);

        // Build the Specification after all the services are added to the server.
        server.addListener(new ServerListenerAdapter() {
            @Override
            public void serverStarting(Server server) throws Exception {
                final ServerConfig config = server.config();
                final List<VirtualHost> virtualHosts = config.findVirtualHosts(DocService.this);

                final List<ServiceEntry> services = config.services().stream()
                                                     .filter(se -> virtualHosts.contains(se.virtualHost()))
                                                     .collect(Collectors.toList());

                vfs().setSpecification(mapper.writerWithDefaultPrettyPrinter()
                                             .writeValueAsBytes(Specification.forServiceEntries(services)));
            }
        });
    }

    DocServiceVfs vfs() {
        return (DocServiceVfs) ((HttpFileService) serviceAt(0)).config().vfs();
    }

    static final class DocServiceVfs implements HttpVfs {

        private volatile Entry entry = Entry.NONE;

        @Override
        public Entry get(String path) {
            // Exact path mapping always translates a matching path to "/"
            assert "/".equals(path);
            return entry;
        }

        void setSpecification(byte[] content) {
            assert entry == Entry.NONE;
            entry = new ByteArrayEntry("/", content);
        }
    }
}
