/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.centraldogma;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.centraldogma.CentralDogmaCodec;
import com.linecorp.armeria.common.centraldogma.CentralDogmaEndpointException;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * A CentralDogma based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a route file served by CentralDogma, and update the list when upstream data changes.
 * Route file could be json file or normal text file.
 *
 * <p>In below example, json file with below content will be served as route file:
 *
 * <pre>{@code
 *  [
 *      "host1:port1",
 *      "host2:port2",
 *      "host3:port3"
 *  ]
 * }
 * </pre>
 *
 * <p>The route file could be retrieve as {@link EndpointGroup} using below code:
 *
 * <pre>{@code
 *  CentralDogmaEndpointGroup<JsonNode> endpointGroup = new CentralDogmaEndpointGroup(
 *      "tbinary+http://localhost:36462/cd/thrift/v1",
 *      "myProject", "myRepo",
 *      entralDogmaCodec.DEFAULT_JSON_CODEC,
 *      Query.ofJsonPath("/route.json")
 *  )
 *  endpointGroup.endpoints();
 * }
 * </pre>
 * @param <T> Type of CentralDomgma file (could be JsonNode or String)
 */
public final class CentralDogmaEndpointGroup<T> extends DynamicEndpointGroup {

    private final CentralDogma dogmaClient;
    private final Watcher<T> dogmaWatcher;
    private final CentralDogmaCodec<T> dogmaCodec;
    private Revision latestRev;

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     * @param uri an uri of CentralDogma server
     * @param dogmaProject CentralDogma project name
     * @param dogmaRepo CentralDogma repository name
     * @param dogmaCodec A {@link CentralDogmaCodec}
     * @param dogmaQuery A {@link Query} to route file
     */
    public CentralDogmaEndpointGroup(String uri, String dogmaProject,
                                     String dogmaRepo, CentralDogmaCodec<T> dogmaCodec,
                                     Query<T> dogmaQuery) {
        this(CentralDogma.newClient(uri), dogmaProject, dogmaRepo, dogmaCodec, dogmaQuery);
    }

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     * @param uri an uri of CentralDogma server
     * @param dogmaProject CentralDogma project name
     * @param dogmaRepo CentralDogma repository name
     * @param dogmaCodec A {@link CentralDogmaCodec}
     * @param dogmaQuery A {@link Query} to route file
     * @param waitTime a {@code long} define how long we should wait for initial result
     * @param waitUnit a {@link TimeUnit} define unit of wait time
     * @throws CentralDogmaEndpointException if couldn't get initial result from CentralDogma server
     */
    public CentralDogmaEndpointGroup(String uri, String dogmaProject,
                                     String dogmaRepo, CentralDogmaCodec<T> dogmaCodec,
                                     Query<T> dogmaQuery, long waitTime, TimeUnit waitUnit)
            throws CentralDogmaEndpointException {
        this(CentralDogma.newClient(uri), dogmaProject, dogmaRepo, dogmaCodec, dogmaQuery, waitTime, waitUnit);
    }

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     * @param dogmaClient A {@link CentralDogma} client
     * @param dogmaProject CentralDogma project name
     * @param dogmaRepo CentralDogma repository name
     * @param dogmaCodec A {@link CentralDogmaCodec}
     * @param dogmaQuery A {@link Query} to route file
     */
    public CentralDogmaEndpointGroup(CentralDogma dogmaClient, String dogmaProject,
                                     String dogmaRepo, CentralDogmaCodec<T> dogmaCodec,
                                     Query<T> dogmaQuery) {
        requireNonNull(dogmaProject);
        requireNonNull(dogmaRepo);
        this.dogmaClient = requireNonNull(dogmaClient, "dogmaClient");
        this.dogmaCodec = requireNonNull(dogmaCodec);
        dogmaWatcher = this.dogmaClient.fileWatcher(dogmaProject, dogmaRepo, dogmaQuery);
        dogmaWatcher.watch((T x) -> {
            setEndpoints(dogmaCodec.decode(x));
        });
    }

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     * @param dogmaClient A {@link CentralDogma} client
     * @param dogmaProject CentralDogma project name
     * @param dogmaRepo CentralDogma repository name
     * @param dogmaCodec A {@link CentralDogmaCodec}
     * @param dogmaQuery A {@link Query} to route file
     * @param waitTime a {@code long} define how long we should wait for initial result
     * @param waitUnit a {@link TimeUnit} define unit of wait time
     * @throws CentralDogmaEndpointException if couldn't get initial result from CentralDogma server
     */
    public CentralDogmaEndpointGroup(CentralDogma dogmaClient, String dogmaProject,
                                     String dogmaRepo, CentralDogmaCodec<T> dogmaCodec,
                                     Query<T> dogmaQuery, long waitTime, TimeUnit waitUnit)
            throws CentralDogmaEndpointException {
        this(dogmaClient, dogmaProject, dogmaRepo, dogmaCodec, dogmaQuery);
        doWait(waitTime, waitUnit);
    }

    private void doWait(long waitTime, TimeUnit waitUnit) throws CentralDogmaEndpointException {
        try {
            this.dogmaWatcher.awaitInitialValue(waitTime, waitUnit);
        } catch (InterruptedException | TimeoutException e) {
            throw new CentralDogmaEndpointException(e);
        }
    }

    @Override
    public void close() {
        dogmaWatcher.close();
    }
}
