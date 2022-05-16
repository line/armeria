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
package com.linecorp.armeria.client.eureka;

import static com.netflix.discovery.shared.transport.EurekaHttpResponse.anEurekaHttpResponse;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import org.junit.Ignore;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.converters.EntityBodyConverter;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientCompatibilityTestSuite;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.internal.common.eureka.EurekaWebClient;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.PortWrapper;

public final class ArmeriaEurekaClientTest extends EurekaHttpClientCompatibilityTestSuite {

    @Override
    protected EurekaHttpClient getEurekaHttpClient(URI serviceURI) {
        return new EurekaHttpClientWrapper(WebClient.of(toH1C(serviceURI)));
    }

    @Override
    protected EurekaHttpClient getEurekaClientWithBasicAuthentication(String userName, String password) {
        final WebClient webClient = WebClient.builder(toH1C(getHttpServer().getServiceURI()))
                                             .auth(AuthToken.ofBasic(userName, password))
                                             .build();
        return new EurekaHttpClientWrapper(webClient);
    }

    private static String toH1C(URI serviceURI) {
        // When an Armeria client sends an HTTP/2 upgrade request, `com.sun.net.httpserver.HttpServer`,
        // the HTTP server implementation used by this test suite, closes the connection prematurely
        // even without `Connection: close` header, violating the HTTP/1.1 specification.
        // As a result, the Armeria client will fail to send the actual request to the test suite server.
        //
        // To work around this problem, we make Armeria client not send an HTTP/2 upgrade request
        // by using the HTTP/1-only scheme.
        //
        // In the real world, the Eureka server will handle persistent HTTP/1 connections properly,
        // so we keep this work around only in this test case.
        return serviceURI.toASCIIString()
                         .replaceFirst("^http:", "h1c:");
    }

    @Ignore
    @Test
    @Override
    public void testStatusUpdateDeleteRequest() throws Exception {
        // not implemented.
    }

    @Ignore
    @Test
    @Override
    public void testStatusUpdateRequest() throws Exception {
        // not implemented.
    }

    private static final class EurekaHttpClientWrapper implements EurekaHttpClient {

        private final EurekaWebClient delegate;
        private final URI eurekaUri;

        EurekaHttpClientWrapper(WebClient webClient) {
            delegate = new EurekaWebClient(webClient);
            eurekaUri = webClient.uri();
        }

        // server
        @Override
        public EurekaHttpResponse<Void> register(InstanceInfo info) {
            return convertVoidResponse(delegate.register(convertInstanceInfo(info)));
        }

        private static com.linecorp.armeria.internal.common.eureka.InstanceInfo convertInstanceInfo(
                InstanceInfo info) {
            final PortWrapper port = new PortWrapper(info.isPortEnabled(PortType.UNSECURE), info.getPort());
            final PortWrapper securePort = new PortWrapper(info.isPortEnabled(PortType.SECURE),
                                                           info.getSecurePort());

            return new com.linecorp.armeria.internal.common.eureka.InstanceInfo(
                    info.getInstanceId(),
                    info.getAppName(), info.getAppGroupName(), info.getHostName(),
                    info.getIPAddr(),
                    info.getVIPAddress(), info.getSecureVipAddress(), port,
                    securePort,
                    com.linecorp.armeria.internal.common.eureka.InstanceInfo.InstanceStatus
                            .toEnum(info.getStatus().name()), info.getHomePageUrl(),
                    info.getStatusPageUrl(),
                    info.getHealthCheckUrl(),
                    info.getSecureHealthCheckUrl(),
                    convertDataCenterInfo(info.getDataCenterInfo()),
                    convertLeaseInfo(info.getLeaseInfo()),
                    info.getMetadata());
        }

        private static com.linecorp.armeria.internal.common.eureka.LeaseInfo convertLeaseInfo(
                LeaseInfo leaseInfo) {
            return new com.linecorp.armeria.internal.common.eureka.LeaseInfo(
                    leaseInfo.getRenewalIntervalInSecs(),
                    leaseInfo.getDurationInSecs(),
                    leaseInfo.getRegistrationTimestamp(),
                    leaseInfo.getRenewalTimestamp(),
                    leaseInfo.getEvictionTimestamp(),
                    leaseInfo.getServiceUpTimestamp());
        }

        @Override
        public EurekaHttpResponse<Void> cancel(String appName, String id) {
            return convertVoidResponse(delegate.cancel(appName, id));
        }

        @Override
        public EurekaHttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info,
                                                              @Nullable InstanceStatus overriddenStatus) {
            return convertResponse(delegate.sendHeartBeat(
                    appName, id, convertInstanceInfo(info),
                    overriddenStatus == null ?
                    null : com.linecorp.armeria.internal.common.eureka.InstanceInfo.InstanceStatus
                            .toEnum(overriddenStatus.name())),
                                   InstanceInfo.class);
        }

        @Override
        public EurekaHttpResponse<Void> statusUpdate(String appName, String id, InstanceStatus newStatus,
                                                     InstanceInfo info) {
            // Not implemented.
            return null;
        }

        @Override
        public EurekaHttpResponse<Void> deleteStatusOverride(String appName, String id, InstanceInfo info) {
            // Not implemented.
            return null;
        }

        // client
        @Override
        public EurekaHttpResponse<Applications> getApplications(String... regions) {
            final EurekaHttpResponse<Applications> eurekaResponse = convertResponse(delegate.getApplications(
                    ImmutableList.copyOf(requireNonNull(regions, "regions"))), Applications.class);

            final EurekaEndpointGroupBuilder builder = EurekaEndpointGroup.builder(eurekaUri);
            setRegions(builder, regions);
            final List<Endpoint> endpoints = builder.build().whenReady().join();
            final Applications applications = eurekaResponse.getEntity();
            assertThat(endpoints).containsExactlyInAnyOrderElementsOf(endpointsFromApplications(applications,
                                                                                                false));
            return eurekaResponse;
        }

        @Override
        public EurekaHttpResponse<Applications> getDelta(String... regions) {
            return convertResponse(delegate.getDelta(
                    ImmutableList.copyOf(requireNonNull(regions, "regions"))), Applications.class);
        }

        @Override
        public EurekaHttpResponse<Applications> getVip(String vipAddress, String... regions) {
            final EurekaHttpResponse<Applications> eurekaResponse = convertResponse(delegate.getVip(
                    vipAddress, ImmutableList.copyOf(requireNonNull(regions, "regions"))), Applications.class);

            final EurekaEndpointGroupBuilder builder = EurekaEndpointGroup.builder(eurekaUri)
                                                                          .vipAddress(vipAddress);
            setRegions(builder, regions);
            final List<Endpoint> endpoints = builder.build().whenReady().join();
            final Applications applications = eurekaResponse.getEntity();
            assertThat(endpoints).containsExactlyInAnyOrderElementsOf(
                    endpointsFromApplications(applications, false));
            return eurekaResponse;
        }

        @Override
        public EurekaHttpResponse<Applications> getSecureVip(String secureVipAddress, String... regions) {
            final EurekaHttpResponse<Applications> eurekaResponse = convertResponse(
                    delegate.getSecureVip(secureVipAddress, ImmutableList.copyOf(
                            requireNonNull(regions, "regions"))), Applications.class);
            final EurekaEndpointGroupBuilder builder = EurekaEndpointGroup.builder(eurekaUri)
                                                                          .secureVipAddress(secureVipAddress);
            setRegions(builder, regions);
            final List<Endpoint> endpoints = builder.build().whenReady().join();
            final Applications applications = eurekaResponse.getEntity();
            assertThat(endpoints).containsExactlyInAnyOrderElementsOf(
                    endpointsFromApplications(applications, true));
            return eurekaResponse;
        }

        @Override
        public EurekaHttpResponse<Application> getApplication(String appName) {
            final EurekaHttpResponse<Application> eurekaResponse =
                    convertResponse(delegate.getApplication(appName), Application.class);

            final EurekaEndpointGroup endpointGroup = EurekaEndpointGroup.builder(eurekaUri)
                                                                         .appName(appName)
                                                                         .build();
            final List<Endpoint> endpoints = endpointGroup.whenReady().join();
            final Application application = eurekaResponse.getEntity();
            assertThat(endpoints).containsExactlyInAnyOrderElementsOf(
                    endpointsFromApplication(application, false));
            return eurekaResponse;
        }

        @Override
        public EurekaHttpResponse<InstanceInfo> getInstance(String appName, String id) {
            final EurekaHttpResponse<InstanceInfo> eurekaResponse =
                    convertResponse(delegate.getInstance(appName, id), InstanceInfo.class);

            final EurekaEndpointGroup endpointGroup = EurekaEndpointGroup.builder(eurekaUri)
                                                                         .appName(appName)
                                                                         .instanceId(id).build();
            final List<Endpoint> endpoints = endpointGroup.whenReady().join();
            final InstanceInfo instanceInfo = eurekaResponse.getEntity();
            final Endpoint endpoint = endpoint(instanceInfo, false);
            assertThat(endpoints).containsOnly(endpoint);
            return eurekaResponse;
        }

        @Override
        public EurekaHttpResponse<InstanceInfo> getInstance(String id) {
            final EurekaHttpResponse<InstanceInfo> eurekaResponse =
                    convertResponse(delegate.getInstance(id), InstanceInfo.class);

            final EurekaEndpointGroup endpointGroup = EurekaEndpointGroup.builder(eurekaUri)
                                                                         .instanceId(id).build();
            final List<Endpoint> endpoints = endpointGroup.whenReady().join();
            final InstanceInfo instanceInfo = eurekaResponse.getEntity();
            final Endpoint endpoint = endpoint(instanceInfo, false);
            assertThat(endpoints).containsOnly(endpoint);
            return eurekaResponse;
        }

        @Override
        public void shutdown() {}

        private static EurekaHttpResponse<Void> convertVoidResponse(HttpResponse response) {
            final AggregatedHttpResponse aggregatedRes = response.aggregate().join();
            return anEurekaHttpResponse(aggregatedRes.status().code())
                    .headers(headersOf(aggregatedRes.headers()))
                    .build();
        }

        private static <T> EurekaHttpResponse<T> convertResponse(HttpResponse response, Class<T> type) {
            final AggregatedHttpResponse aggregatedRes = response.aggregate().join();
            T t = null;
            final ResponseHeaders headers = aggregatedRes.headers();
            if (headers.status() == HttpStatus.OK) {
                final EntityBodyConverter converter = new EntityBodyConverter();
                try {
                    // noinspection unchecked
                    t = (T) converter.read(
                            aggregatedRes.content().toInputStream(), type,
                            MediaType.valueOf(headers.contentType().toString()));
                } catch (IOException e) {
                    throw new RuntimeException("Unexpected exception while converting response body:", e);
                }
            }

            return anEurekaHttpResponse(aggregatedRes.status().code(), t)
                    .headers(headersOf(headers))
                    .build();
        }

        private static Map<String, String> headersOf(ResponseHeaders headers) {
            final Map<String, String> result = new HashMap<>();
            headers.forEach(h -> result.put(h.getKey().toString(), h.getValue()));
            return result;
        }

        private static com.linecorp.armeria.internal.common.eureka.DataCenterInfo convertDataCenterInfo(
                DataCenterInfo dataCenterInfo) {
            final Map<String, String> metadata;
            if (dataCenterInfo.getName() == Name.Amazon) {
                metadata = ((AmazonInfo) dataCenterInfo).getMetadata();
            } else {
                metadata = ImmutableMap.of();
            }
            return new com.linecorp.armeria.internal.common.eureka.DataCenterInfo(
                    dataCenterInfo.getName().name(), metadata);
        }

        private static void setRegions(EurekaEndpointGroupBuilder builder, String... regions) {
            if (regions.length > 0) {
                builder.regions(regions);
            }
        }

        private static List<Endpoint> endpointsFromApplications(Applications applications, boolean secureVip) {
            final Builder<Endpoint> builder = ImmutableList.builder();
            for (Application application : applications.getRegisteredApplications()) {
                builder.addAll(endpointsFromApplication(application, secureVip));
            }
            return builder.build();
        }

        private static List<Endpoint> endpointsFromApplication(Application application, boolean secureVip) {
            final Builder<Endpoint> builder = ImmutableList.builder();
            for (InstanceInfo instance : application.getInstances()) {
                builder.add(endpoint(instance, secureVip));
            }
            return builder.build();
        }

        private static Endpoint endpoint(InstanceInfo instanceInfo, boolean secureVip) {
            return Endpoint.of(instanceInfo.getHostName(),
                               secureVip ? instanceInfo.getSecurePort() : instanceInfo.getPort())
                           .withIpAddr(instanceInfo.getIPAddr());
        }
    }
}
