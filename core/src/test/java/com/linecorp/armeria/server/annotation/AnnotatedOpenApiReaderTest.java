/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static com.linecorp.armeria.server.annotation.AnnotatedOpenApiReader.endpointPath;
import static com.linecorp.armeria.server.annotation.AnnotatedOpenApiReader.fillFromOpenApiDefinition;
import static com.linecorp.armeria.server.annotation.AnnotatedOpenApiReader.fillFromService;
import static com.linecorp.armeria.server.annotation.AnnotatedOpenApiReader.operation;
import static com.linecorp.armeria.server.annotation.AnnotatedValueResolver.toRequestObjectResolvers;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.AnnotatedHttpServiceFactory.PrefixAddingPathMapping;

import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.ServerVariable;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header.StyleEnum;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

public class AnnotatedOpenApiReaderTest {

    private ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(Include.NON_NULL);

    @Test
    public void openApiDefinition() throws JsonProcessingException {
        @OpenAPIDefinition(
                info = @Info(
                        title = "Armeria",
                        description = "Armeria+OpenAPI",
                        contact = @Contact(url = "http://foo.com", name = "min", email = "min@min.com"),
                        license = @License(name = "Apache 2.0", url = "http://foo.bar"),
                        version = "1.0"
                ),
                tags = {
                        @Tag(name = "my tag", description = "my description ",
                             externalDocs = @ExternalDocumentation(description = "ext desc", url = "a.com")),
                },
                externalDocs = @ExternalDocumentation(description = "definition docs desc",
                                                      url = "https://my-example.com"),
                servers = {
                        @io.swagger.v3.oas.annotations.servers.Server(
                                description = "server desc",
                                url = "https://{environment}.example.com/v2",
                                variables = {
                                        @ServerVariable(name = "environment", description = "environment var",
                                                        defaultValue = "api",
                                                        allowableValues = { "api", "api.beta", "api.staging" })
                                })
                }
        )
        class OpenApiDefClass {}

        final OpenAPI openApi = new OpenAPI();
        fillFromOpenApiDefinition(openApi, OpenApiDefClass.class);
        final String expectedJson = '{' +
                                    "  \"openapi\" : \"3.0.1\"," + // Set by default.
                                    "  \"info\" : {" +
                                    "    \"title\" : \"Armeria\"," +
                                    "    \"description\" : \"Armeria+OpenAPI\"," +
                                    "    \"contact\" : {" +
                                    "      \"name\" : \"min\"," +
                                    "      \"url\" : \"http://foo.com\"," +
                                    "      \"email\" : \"min@min.com\"" +
                                    "    }," +
                                    "    \"license\" : {" +
                                    "      \"name\" : \"Apache 2.0\"," +
                                    "      \"url\" : \"http://foo.bar\"" +
                                    "    }," +
                                    "    \"version\" : \"1.0\"" +
                                    "  }," +
                                    "  \"externalDocs\" : {" +
                                    "    \"description\" : \"definition docs desc\"," +
                                    "    \"url\" : \"https://my-example.com\"" +
                                    "  }," +
                                    "  \"servers\" : [ {" +
                                    "    \"url\" : \"https://{environment}.example.com/v2\"," +
                                    "    \"description\" : \"server desc\"," +
                                    "    \"variables\" : {" +
                                    "      \"environment\" : {" +
                                    "        \"description\" : \"environment var\"," +
                                    "        \"default\" : \"api\"," +
                                    "        \"enum\" : [ \"api\", \"api.beta\", \"api.staging\" ]" +
                                    "      }" +
                                    "    }" +
                                    "  } ]," +
                                    "  \"tags\" : [ {" +
                                    "    \"name\" : \"my tag\"," +
                                    "    \"description\" : \"my description \"," +
                                    "    \"externalDocs\" : {" +
                                    "      \"description\" : \"ext desc\"," +
                                    "      \"url\" : \"a.com\"" +
                                    "    }" +
                                    "  } ]" +
                                    '}';
        assertThatJson(mapper.writeValueAsString(openApi)).isEqualTo(expectedJson);
    }

    @Test
    public void pathVariableInUrlShouldBeSpecified() {
        @OpenAPIDefinition(
                servers = {
                        @io.swagger.v3.oas.annotations.servers.Server(
                                description = "server desc",
                                url = "https://{environment}.example.com/v2",
                                variables = {
                                        @ServerVariable(name = "envir", description = "environment var",
                                                        defaultValue = "api",
                                                        allowableValues = { "api", "api.beta", "api.staging" })
                                })
                }
        )
        class OpenApiDefClass {}

        final OpenAPI openApi = new OpenAPI();
        assertThatThrownBy(() -> fillFromOpenApiDefinition(openApi, OpenApiDefClass.class))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not specified in the server variable.");
    }

    @Test
    public void externalDocumentShouldHaveUrl() {
        @OpenAPIDefinition(
                externalDocs = @ExternalDocumentation(description = "definition docs desc")
        )
        class OpenApiDefClass {}

        final OpenAPI openApi = new OpenAPI();
        assertThatThrownBy(() -> fillFromOpenApiDefinition(openApi, OpenApiDefClass.class))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A URL is required");
    }

    @Test
    public void testEndpointPath() {
        PathMapping mapping = newHttpHeaderPathMapping(PathMapping.of("/path"));
        String endpointPath = endpointPath(mapping);
        assertThat(endpointPath).isEqualTo("/path");

        mapping = newHttpHeaderPathMapping(PathMapping.of("exact:/:foo/bar"));
        endpointPath = endpointPath(mapping);
        assertThat(endpointPath).isEqualTo("/:foo/bar");

        mapping = newHttpHeaderPathMapping(PathMapping.of("prefix:/bar/baz"));
        endpointPath = endpointPath(mapping);
        assertThat(endpointPath).isEqualTo("/bar/baz/");

        mapping = newHttpHeaderPathMapping(PathMapping.of("/service/{value}/test/:value2/something"));
        endpointPath = endpointPath(mapping);
        assertThat(endpointPath).isEqualTo("/service/{value}/test/{value2}/something");

        mapping = newHttpHeaderPathMapping(PathMapping.of("glob:/home/*/files/**"));
        endpointPath = endpointPath(mapping);
        // Does not support regex
        assertThat(endpointPath).isNull();

        mapping = newHttpHeaderPathMapping(
                new PrefixAddingPathMapping("/prefix: regex:/",
                                            PathMapping.of("regex:^/files/(?<filePath>.*)$")));
        endpointPath = endpointPath(mapping);
        // Does not support PrefixAddingPathMapping
        assertThat(endpointPath).isNull();
    }

    private static PathMapping newHttpHeaderPathMapping(PathMapping pathMapping) {
        return PathMapping.withHttpHeaderInfo(pathMapping, ImmutableSet.of(HttpMethod.GET),
                                              ImmutableList.of(PLAIN_TEXT_UTF_8),
                                              ImmutableList.of(JSON_UTF_8));
    }

    @Test
    public void testParameters() {
        class OperationTest {
            @Patch("/patch/{arg3}")
            public String foo(@Param("arg1") int arg1, @Header("arg2") Optional<Long> arg2,
                              @Param("arg3") String arg3, Cookies arg4,
                              // The parameters under this line are ignored.
                              RequestContext arr5, ServiceRequestContext arg6, Request arg7,
                              HttpRequest arg8, AggregatedHttpMessage arg9, HttpParameters arg10) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of("arg3"));
        final String[] produceTypes = new String[] { JSON_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, new String[0], produceTypes);

        final List<Parameter> expected =
                ImmutableList.of(new Parameter().name("arg1").in("query").required(true)
                                                .schema(new IntegerSchema().format("int32")),
                                 new Parameter().name("arg2").in("header").required(false)
                                                .schema(new IntegerSchema().format("int64")),
                                 new Parameter().name("arg3").in("path").required(true)
                                                .schema(new StringSchema()),
                                 new Parameter().name("cookies").in("cookie").required(true)
                                                .schema(new StringSchema()));
        assertThat(operation.getParameters()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static AnnotatedHttpService annotatedHttpService(Class<?> clazz, Set<String> pathParams) {
        final Method method = clazz.getDeclaredMethods()[0];
        final List<AnnotatedValueResolver> resolvers =
                AnnotatedValueResolver.ofServiceMethod(method, pathParams,
                                                       toRequestObjectResolvers(ImmutableList.of()));
        final AnnotatedHttpService service = mock(AnnotatedHttpService.class);
        when(service.method()).thenReturn(method);
        when(service.annotatedValueResolvers()).thenReturn(resolvers);
        return service;
    }

    @Test
    public void testOperation() {
        class OperationTest {
            @Patch("/patch")
            @io.swagger.v3.oas.annotations.Operation(tags = { "tag1", "tag2" }, summary = "foo summary",
                                                     description = "foo description", operationId = "fooId",
                                                     externalDocs = @ExternalDocumentation(
                                                             description = "ext doc", url = "a.com"))
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] produceTypes = new String[] { JSON_UTF_8.toString(), PLAIN_TEXT_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, new String[0], produceTypes);

        final List<Parameter> parameters = ImmutableList.of(
                new Parameter().name("arg1").in("query").required(true)
                               .schema(new IntegerSchema().format("int32")));
        final ApiResponses responses = new ApiResponses()._default(
                new ApiResponse().description("default response").content(
                        new Content().addMediaType("application/json; charset=utf-8",
                                                   new MediaType().schema(new StringSchema()))
                                     .addMediaType("text/plain; charset=utf-8",
                                                   new MediaType().schema(new StringSchema()))));
        final io.swagger.v3.oas.models.ExternalDocumentation extDoc =
                new io.swagger.v3.oas.models.ExternalDocumentation().description("ext doc").url("a.com");
        final Operation expected = new Operation().tags(ImmutableList.of("tag1", "tag2")).summary("foo summary")
                                                  .description("foo description")
                                                  .operationId("fooId").externalDocs(extDoc)
                                                  .parameters(parameters).responses(responses);

        assertThat(operation).isEqualTo(expected);
    }

    @Test
    public void operationResponse() {
        class OperationTest {
            @Get("/get")
            @io.swagger.v3.oas.annotations.Operation(
                    responses = {
                            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                                    description = "ok response", responseCode = "200", headers = {
                                    @io.swagger.v3.oas.annotations.headers.Header(
                                            name = "success-header", description = "succeeded")
                            }),
                            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                                    description = "error response", responseCode = "500")
                    })
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] produceTypes = new String[] { JSON_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, new String[0], produceTypes);
        final ApiResponses responses = new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok response").headers(
                        ImmutableMap.of("success-header",
                                        new io.swagger.v3.oas.models.headers.Header().description("succeeded")
                                                                                     .style(StyleEnum.SIMPLE))))
                .addApiResponse("500", new ApiResponse().description("error response"));

        assertThat(operation.getResponses()).isEqualTo(responses);
    }

    @Test
    public void getClassResponseWhenMethodDoesNotHave() {
        @io.swagger.v3.oas.annotations.responses.ApiResponses(
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        description = "ok response", responseCode = "200", headers = {
                        @io.swagger.v3.oas.annotations.headers.Header(
                                name = "success-header", description = "succeeded")
                }))
        class OperationTest {
            @Get("/get")
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] produceTypes = new String[] { JSON_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, new String[0], produceTypes);

        final ApiResponses responses = new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok response").headers(
                        ImmutableMap.of("success-header",
                                        new io.swagger.v3.oas.models.headers.Header()
                                                .description("succeeded").style(StyleEnum.SIMPLE))));

        assertThat(operation.getResponses()).isEqualTo(responses);
    }

    @Test
    public void requestBody() {
        class OperationTest {
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
                    @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "text/plain; charset=utf-8")
            })
            @Get("/get")
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] produceTypes = new String[] { JSON_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, new String[0], produceTypes);
        final RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("text/plain; charset=utf-8", new MediaType()))
                .required(false);

        assertThat(operation.getRequestBody()).isEqualTo(requestBody);
    }

    @Test
    public void operationRequestBody() throws JsonProcessingException {
        class OperationTest {
            @Get("/get")
            @io.swagger.v3.oas.annotations.Operation(
                    requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
                            @io.swagger.v3.oas.annotations.media.Content(
                                    mediaType = "text/plain; charset=utf-8")
                    }
                    ))
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] produceTypes = new String[] { JSON_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, new String[0], produceTypes);

        final List<Parameter> parameters = ImmutableList.of(
                new Parameter().name("arg1").in("query").required(true)
                               .schema(new IntegerSchema().format("int32")));

        final ApiResponses responses = new ApiResponses()._default(
                new ApiResponse().description("default response").content(
                        new Content().addMediaType("application/json; charset=utf-8",
                                                   new MediaType().schema(new StringSchema()))));
        final RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("text/plain; charset=utf-8", new MediaType()))
                .required(false);

        final Operation expected = new Operation().operationId("foo")
                                                  .parameters(parameters)
                                                  .requestBody(requestBody)
                                                  .responses(responses);

        assertThat(operation).isEqualTo(expected);
    }

    @Test
    public void consumesAreUsedWhenContentIsnotSpecified() throws JsonProcessingException {
        @ConsumesBinary
        class OperationTest {

            @ConsumesOctetStream
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
                    @io.swagger.v3.oas.annotations.media.Content
            })
            @Get("/get")
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] consumeTypes = new String[] { "application/binary", "application/octet-stream" };
        final String[] produceTypes = new String[] { JSON_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()), OperationTest.class,
                                              service, consumeTypes, produceTypes);

        final RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/binary", new MediaType())
                                      .addMediaType("application/octet-stream", new MediaType()))
                .required(false);

        assertThat(operation.getRequestBody()).isEqualTo(requestBody);
    }

    @Test
    public void deprecatedOperation() {
        class OperationTest {
            /**
             * Deprecated.
             *
             * @deprecated deprecated test
             */
            @Patch("/patch")
            @Deprecated
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final AnnotatedHttpService service = annotatedHttpService(OperationTest.class, ImmutableSet.of());
        final String[] produceTypes = new String[] { JSON_UTF_8.toString(), PLAIN_TEXT_UTF_8.toString() };
        final Operation operation = operation(new OpenAPI().components(new Components()),
                                              OperationTest.class,
                                              service, new String[0], produceTypes);
        assertThat(operation.getDeprecated()).isTrue();
    }

    @Test
    public void testFillFromService() throws Exception {
        @OpenAPIDefinition(
                info = @Info(
                        title = "Armeria",
                        description = "Armeria+OpenAPI",
                        contact = @Contact(url = "http://foo.com", name = "min", email = "min@min.com"),
                        license = @License(name = "Apache 2.0", url = "http://foo.bar"),
                        version = "1.0"
                ),
                tags = {
                        @Tag(name = "my tag", description = "my description ",
                             externalDocs = @ExternalDocumentation(description = "ext desc",
                                                                   url = "a.com")),
                }
        )
        class FillFromService {
            @Post
            @Get
            @Path("/foo")
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
                    @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "text/plain; charset=utf-8")
            })
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    description = "ok response", responseCode = "200", headers = {
                    @io.swagger.v3.oas.annotations.headers.Header(
                            name = "success-header", description = "succeeded")
            })
            public String foo(@Param("arg1") int arg1) {
                return "";
            }
        }

        final Method method = FillFromService.class.getDeclaredMethods()[0];
        final List<AnnotatedValueResolver> resolvers =
                AnnotatedValueResolver.ofServiceMethod(method, ImmutableSet.of(),
                                                       toRequestObjectResolvers(ImmutableList.of()));
        final PathMapping pathMapping = PathMapping.withHttpHeaderInfo(
                PathMapping.of("/foo"), ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                ImmutableList.of(PLAIN_TEXT_UTF_8), ImmutableList.of(JSON_UTF_8));

        final AnnotatedHttpService service = new AnnotatedHttpService(new FillFromService(), method,
                                                                      resolvers,
                                                                      ImmutableList.of(),
                                                                      ImmutableList.of(),
                                                                      pathMapping);

        final OpenAPI openApi = new OpenAPI().components(new Components());
        fillFromService(openApi, service);

        final String expectedJson = '{' +
                                    "  \"openapi\" : \"3.0.1\"," +
                                    "  \"info\" : {" +
                                    "    \"title\" : \"Armeria\"," +
                                    "    \"description\" : \"Armeria+OpenAPI\"," +
                                    "    \"contact\" : {" +
                                    "      \"name\" : \"min\"," +
                                    "      \"url\" : \"http://foo.com\"," +
                                    "      \"email\" : \"min@min.com\"" +
                                    "    }," +
                                    "    \"license\" : {" +
                                    "      \"name\" : \"Apache 2.0\"," +
                                    "      \"url\" : \"http://foo.bar\"" +
                                    "    }," +
                                    "    \"version\" : \"1.0\"" +
                                    "  }," +
                                    "  \"tags\" : [ {" +
                                    "    \"name\" : \"my tag\"," +
                                    "    \"description\" : \"my description \"," +
                                    "    \"externalDocs\" : {" +
                                    "      \"description\" : \"ext desc\"," +
                                    "      \"url\" : \"a.com\"" +
                                    "    }" +
                                    "  } ]," +
                                    "  \"paths\" : {" +
                                    "    \"/foo\" : {" +
                                    "      \"get\" : {" +
                                    "        \"operationId\" : \"foo\"," +
                                    "        \"parameters\" : [ {" +
                                    "          \"name\" : \"arg1\"," +
                                    "          \"in\" : \"query\"," +
                                    "          \"required\" : true," +
                                    "          \"schema\" : {" +
                                    "            \"type\" : \"integer\"," +
                                    "            \"format\" : \"int32\"" +
                                    "          }" +
                                    "        } ]," +
                                    "        \"requestBody\" : {" +
                                    "          \"content\" : {" +
                                    "            \"text/plain; charset=utf-8\" : { }" +
                                    "          }," +
                                    "          \"required\" : false" +
                                    "        }," +
                                    "        \"responses\" : {" +
                                    "          \"200\" : {" +
                                    "            \"description\" : \"ok response\"," +
                                    "            \"headers\" : {" +
                                    "              \"success-header\" : {" +
                                    "                \"description\" : \"succeeded\"," +
                                    "                \"style\" : \"SIMPLE\"" +
                                    "              }" +
                                    "            }" +
                                    "          }" +
                                    "        }" +
                                    "      }," +
                                    "      \"post\" : {" +
                                    "        \"operationId\" : \"foo_1\"," +
                                    "        \"parameters\" : [ {" +
                                    "          \"name\" : \"arg1\"," +
                                    "          \"in\" : \"query\"," +
                                    "          \"required\" : true," +
                                    "          \"schema\" : {" +
                                    "            \"type\" : \"integer\"," +
                                    "            \"format\" : \"int32\"" +
                                    "          }" +
                                    "        } ]," +
                                    "        \"requestBody\" : {" +
                                    "          \"content\" : {" +
                                    "            \"text/plain; charset=utf-8\" : { }" +
                                    "          }," +
                                    "          \"required\" : false" +
                                    "        }," +
                                    "        \"responses\" : {" +
                                    "          \"200\" : {" +
                                    "            \"description\" : \"ok response\"," +
                                    "            \"headers\" : {" +
                                    "              \"success-header\" : {" +
                                    "                \"description\" : \"succeeded\"," +
                                    "                \"style\" : \"SIMPLE\"" +
                                    "              }" +
                                    "            }" +
                                    "          }" +
                                    "        }" +
                                    "      }" +
                                    "    }" +
                                    "  }," +
                                    "  \"components\" : { }" +
                                    '}';
        assertThatJson(mapper.writeValueAsString(openApi)).isEqualTo(expectedJson);
    }
}
