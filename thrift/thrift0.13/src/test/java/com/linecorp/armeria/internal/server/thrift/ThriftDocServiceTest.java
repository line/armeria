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
package com.linecorp.armeria.internal.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.internal.server.thrift.ThriftDocStringTestUtil.assumeDocStringsAvailable;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.Entry;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.EntryBuilder;
import com.linecorp.armeria.internal.testing.DocServiceExtension;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import testing.thrift.hbase.Hbase;
import testing.thrift.main.FileService;
import testing.thrift.main.FooService;
import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.hello_args;
import testing.thrift.main.MidLineTagTestService;
import testing.thrift.main.OnewayHelloService;
import testing.thrift.main.SleepService;

@ExtendWith(DocServiceExtension.class)
public class ThriftDocServiceTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final SleepService.AsyncIface SLEEP_SERVICE_HANDLER =
            (duration, resultHandler) -> resultHandler.onComplete(duration);

    private static final hello_args EXAMPLE_HELLO = new hello_args("sample user");
    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(HttpHeaderNames.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_HELLO = HttpHeaders.of(HttpHeaderNames.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_FOO = HttpHeaders.of(HttpHeaderNames.of("e"), "f");
    private static final HttpHeaders EXAMPLE_HEADERS_FOO_BAR1 = HttpHeaders.of(HttpHeaderNames.of("g"), "h");

    private static final ObjectMapper mapper = new ObjectMapper();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            final THttpService helloAndSleepService =
                    THttpService.builder()
                                .addService("hello", HELLO_SERVICE_HANDLER)
                                .addService("sleep", SLEEP_SERVICE_HANDLER)
                                .build();
            final THttpService fooService =
                    THttpService.ofFormats(mock(FooService.AsyncIface.class), COMPACT);
            final THttpService hbaseService =
                    THttpService.of(mock(Hbase.AsyncIface.class));
            final THttpService onewayHelloService =
                    THttpService.of(mock(OnewayHelloService.AsyncIface.class));
            final THttpService fileService =
                    THttpService.of(mock(FileService.AsyncIface.class));
            final THttpService midLineTagTestService =
                    THttpService.of(mock(MidLineTagTestService.AsyncIface.class));

            sb.service("/", helloAndSleepService);
            sb.service("/foo", fooService);
            // Add a service with serviceUnder() to test whether prefix mapping is detected.
            sb.serviceUnder("/foo", fooService);
            sb.service("/hbase", hbaseService);
            sb.service("/oneway", onewayHelloService);
            sb.service("/file", fileService);
            sb.service("/midline", midLineTagTestService);

            sb.serviceUnder(
                    "/docs/",
                    DocService.builder()
                              .exampleHeaders(EXAMPLE_HEADERS_ALL)
                              .exampleHeaders(HelloService.class, EXAMPLE_HEADERS_HELLO)
                              .exampleHeaders(FooService.class, EXAMPLE_HEADERS_FOO)
                              .exampleHeaders(FooService.class, "bar1", EXAMPLE_HEADERS_FOO_BAR1)
                              .exampleRequests(ImmutableList.of(EXAMPLE_HELLO))
                              .build());
            sb.serviceUnder(
                    "/excludeAll/",
                    DocService.builder()
                              .exclude(DocServiceFilter.ofThrift())
                              .build());
        }
    };

    @Test
    public void testOk() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final Set<SerializationFormat> allThriftFormats = ThriftSerializationFormats.values();
        final List<Entry> entries = ImmutableList.of(
                new EntryBuilder(HelloService.class)
                        .endpoint(EndpointInfo.builder("*", "/")
                                              .fragment("hello")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(SleepService.class)
                        .endpoint(EndpointInfo.builder("*", "/")
                                              .fragment("sleep")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(FooService.class)
                        .endpoint(EndpointInfo.builder("*", "/foo").defaultFormat(COMPACT).build())
                        .endpoint(EndpointInfo.builder("*", "/foo/").defaultFormat(COMPACT).build())
                        .build(),
                new EntryBuilder(Hbase.class)
                        .endpoint(EndpointInfo.builder("*", "/hbase")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(MidLineTagTestService.class)
                        .endpoint(EndpointInfo.builder("*", "/midline")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(OnewayHelloService.class)
                        .endpoint(EndpointInfo.builder("*", "/oneway")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(FileService.class)
                        .endpoint(EndpointInfo.builder("*", "/file")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build());

        final JsonNode expectedJson = mapper.valueToTree(
                new ThriftDocServicePlugin().generate(entries, (plugin, service, method) -> true,
                                                      new ThriftDescriptiveTypeInfoProvider()));

        // The specification generated by ThriftDocServicePlugin does not include the examples specified
        // when building a DocService, so we add them manually here.
        addExamples(expectedJson);

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        // The specification generated by ThriftDocServicePlugin does not include the docstrings
        // because it's injected by the DocService, so we remove them here for easier comparison.
        removeDescriptionInfos(actualJson);
        removeDescriptionInfos(expectedJson);

        // Ignore docServiceRoute (route path) and docStrings (loaded by DocStringSupport, not plugin)
        assertThatJson(actualJson).whenIgnoringPaths("docServiceRoute", "docStrings").isEqualTo(expectedJson);
    }

    private static void addExamples(JsonNode json) {
        // Add the global example.
        ((ArrayNode) json.get("exampleHeaders")).add(mapper.valueToTree(EXAMPLE_HEADERS_ALL));

        json.get("services").forEach(service -> {
            // Add the service-wide examples.
            final String serviceName = service.get("name").textValue();
            final ArrayNode serviceExampleHeaders = (ArrayNode) service.get("exampleHeaders");
            if (HelloService.class.getName().equals(serviceName)) {
                serviceExampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_HELLO));
            }
            if (FooService.class.getName().equals(serviceName)) {
                serviceExampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_FOO));
            }

            // Add the method-specific examples.
            service.get("methods").forEach(method -> {
                final String methodName = method.get("name").textValue();
                final ArrayNode exampleHeaders = (ArrayNode) method.get("exampleHeaders");
                if (FooService.class.getName().equals(serviceName) &&
                    "bar1".equals(methodName)) {
                    exampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_FOO_BAR1));
                }

                final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");
                if (HelloService.class.getName().equals(serviceName) &&
                    "hello".equals(methodName)) {
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"name\" : \"sample user\"" + System.lineSeparator() +
                                        '}');
                }
            });
        });
    }

    private static void removeDescriptionInfos(JsonNode json) {
        if (json.isObject()) {
            ((ObjectNode) json).remove("descriptionInfo");
        }

        if (json.isObject() || json.isArray()) {
            json.forEach(ThriftDocServiceTest::removeDescriptionInfos);
        }
    }

    @Test
    public void excludeAllServices() throws IOException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/excludeAll/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final Route docServiceRoute = Route.builder().pathPrefix("/excludeAll").build();
        final JsonNode expectedJson = mapper.valueToTree(new ServiceSpecification(ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  docServiceRoute));
        // Note: docStrings are loaded from all services on the virtual host regardless of the filter,
        // so we ignore them in this comparison. This test focuses on verifying services are excluded.
        assertThatJson(actualJson).whenIgnoringPaths("docStrings").isEqualTo(expectedJson);
    }

    @Test
    public void testMethodNotAllowed() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of(server.uri(SessionProtocol.H1C, SerializationFormat.NONE))
                         .blocking().post("/docs/specification.json", "");
        assertThat(res.status().code()).isEqualTo(405);
    }

    @Test
    public void testReturnInfoAndExceptionsJsonStructure() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final JsonNode servicesNode = actualJson.get("services");
        assertThat(servicesNode).isNotNull();

        // Verify that all methods have returnTypeSignature and exceptionTypeSignatures
        // with the expected structure
        for (JsonNode service : servicesNode) {
            for (JsonNode method : service.get("methods")) {
                // Every method should have a returnTypeSignature (string)
                final JsonNode returnTypeSignature = method.get("returnTypeSignature");
                assertThat(returnTypeSignature).isNotNull();
                assertThat(returnTypeSignature.isTextual()).isTrue();

                // Every method should have an exceptionTypeSignatures list (array of strings)
                final JsonNode exceptionTypeSignatures = method.get("exceptionTypeSignatures");
                assertThat(exceptionTypeSignatures).isNotNull();
                assertThat(exceptionTypeSignatures.isArray()).isTrue();
                // Each exception should be a type signature string
                for (JsonNode exception : exceptionTypeSignatures) {
                    assertThat(exception.isTextual()).isTrue();
                }
            }
        }
    }

    @Test
    public void testReturnInfoAndExceptionStructure() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final JsonNode servicesNode = actualJson.get("services");
        assertThat(servicesNode).isNotNull();

        // Verify that HelloService.hello has the expected return type
        JsonNode helloService = null;
        for (JsonNode service : servicesNode) {
            if ("testing.thrift.main.HelloService".equals(service.get("name").textValue())) {
                helloService = service;
                break;
            }
        }
        assertThat(helloService).isNotNull();

        final JsonNode helloMethod = helloService.get("methods").get(0);
        assertThat(helloMethod.get("name").textValue()).isEqualTo("hello");
        final JsonNode helloReturnTypeSignature = helloMethod.get("returnTypeSignature");
        assertThat(helloReturnTypeSignature.textValue()).isEqualTo("string");

        // Verify that FileService.create has the expected exception type
        JsonNode fileService = null;
        for (JsonNode service : servicesNode) {
            if ("testing.thrift.main.FileService".equals(service.get("name").textValue())) {
                fileService = service;
                break;
            }
        }
        assertThat(fileService).isNotNull();

        final JsonNode createMethod = fileService.get("methods").get(0);
        assertThat(createMethod.get("name").textValue()).isEqualTo("create");
        final JsonNode exceptionTypeSignatures = createMethod.get("exceptionTypeSignatures");
        assertThat(exceptionTypeSignatures.size()).isEqualTo(1);
        assertThat(exceptionTypeSignatures.get(0).textValue())
                .isEqualTo("testing.thrift.main.FileServiceException");
    }

    @Test
    public void docStringsAreLoaded() throws Exception {
        assumeDocStringsAvailable();

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode actualJson = mapper.readTree(res.contentUtf8());

        // Build expected docStrings for all services in testing.thrift.main namespace
        // Note: Only Javadoc-style comments (/** ... */) are included, not single-line comments (//)
        // The method docstrings include the full comment with @param, @return, @throws
        final ObjectNode expectedDocStrings = mapper.createObjectNode();

        // HelloService docstrings (has Javadoc comments)
        expectedDocStrings.set("testing.thrift.main.HelloService",
                              descriptionInfo("Tests a non-oneway method with a return value."));
        expectedDocStrings.set("testing.thrift.main.HelloService/hello",
                              descriptionInfo("Sends a greeting to the specified name.\n" +
                                             "@param string name - the name to greet\n" +
                                             "@return a greeting message"));
        expectedDocStrings.set("testing.thrift.main.HelloService/hello:param/name",
                              descriptionInfo("the name to greet"));
        expectedDocStrings.set("testing.thrift.main.HelloService/hello:return",
                              descriptionInfo("a greeting message"));

        // OnewayHelloService - no Javadoc comments, only single-line comment which is not included

        // FooService - no Javadoc comments on the service or methods

        // FileService docstrings (has Javadoc comments)
        expectedDocStrings.set("testing.thrift.main.FileService",
                              descriptionInfo("Tests exception handling."));
        expectedDocStrings.set("testing.thrift.main.FileService/create",
                              descriptionInfo("Creates a file at the specified path.\n" +
                                             "@param string path - the path to create\n" +
                                             "@throws FileServiceException - when the file cannot be created"));
        expectedDocStrings.set("testing.thrift.main.FileService/create:param/path",
                              descriptionInfo("the path to create"));
        expectedDocStrings.set("testing.thrift.main.FileService/create:throws/" +
                              "testing.thrift.main.FileServiceException",
                              descriptionInfo("when the file cannot be created"));

        // FileServiceException docstring
        expectedDocStrings.set("testing.thrift.main.FileServiceException",
                              descriptionInfo("Exception thrown by FileService."));

        // MidLineTagTestService docstrings (has Javadoc comments)
        // Note: The service itself has only a single-line comment, so no service-level docstring
        expectedDocStrings.set("testing.thrift.main.MidLineTagTestService/throwsTypeOnly",
                              descriptionInfo("Method where only the type is specified in the throws tag.\n" +
                                             "@param string value - the input value\n" +
                                             "@throws FooServiceException"));
        expectedDocStrings.set("testing.thrift.main.MidLineTagTestService/throwsTypeOnly:param/value",
                              descriptionInfo("the input value"));
        expectedDocStrings.set("testing.thrift.main.MidLineTagTestService/midLineTagsIgnored",
                              descriptionInfo("Method with mid-line @return should be ignored because tags " +
                                             "must be at line start.\n" +
                                             "Similarly, mid-line @throws FooServiceException - " +
                                             "should also be ignored.\n" +
                                             "@param string value - the input value\n" +
                                             "@return valid return description"));
        expectedDocStrings.set("testing.thrift.main.MidLineTagTestService/midLineTagsIgnored:param/value",
                              descriptionInfo("the input value"));
        expectedDocStrings.set("testing.thrift.main.MidLineTagTestService/midLineTagsIgnored:return",
                              descriptionInfo("valid return description"));

        // Filter actual docStrings to only include testing.thrift.main namespace
        final JsonNode actualDocStrings = actualJson.get("docStrings");
        assertThat(actualDocStrings).isNotNull();

        final ObjectNode filteredActualDocStrings = mapper.createObjectNode();
        actualDocStrings.fields().forEachRemaining(entry -> {
            final String key = entry.getKey();
            if (key.startsWith("testing.thrift.main.")) {
                filteredActualDocStrings.set(key, entry.getValue());
            }
        });

        // Compare using assertThatJson for clear diff output
        assertThatJson(filteredActualDocStrings).isEqualTo(expectedDocStrings);
    }

    private ObjectNode descriptionInfo(String docString) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("docString", docString);
        node.put("markup", "NONE");
        return node;
    }
}
