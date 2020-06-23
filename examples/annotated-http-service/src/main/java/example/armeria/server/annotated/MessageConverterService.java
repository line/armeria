package example.armeria.server.annotated;

import java.lang.reflect.ParameterizedType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

/**
 * Examples how to use {@link RequestConverter} and {@link ResponseConverter}.
 *
 * @see <a href="https://armeria.dev/docs/server-annotated-service#conversion-between-an-http-message-and-a-java-object">
 *      Conversion between an HTTP message and a Java object</a>
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
public class MessageConverterService {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns a {@link JsonNode} which is converted from a {@link Response} object. The {@link JsonNode}
     * will be converted as JSON string by the {@link JacksonResponseConverterFunction}.
     */
    @Post("/node/node")
    public JsonNode json1(@RequestObject JsonNode input) {
        final JsonNode name = input.get("name");
        return mapper.valueToTree(new Response(Response.SUCCESS, name.textValue()));
    }

    /**
     * Returns a {@link Response} object. The {@link ProducesJson} annotation makes an object be converted
     * as JSON string by the {@link JacksonResponseConverterFunction}.
     */
    @Post("/node/obj")
    @ProducesJson
    public Response json2(@RequestObject JsonNode input) {
        final JsonNode name = input.get("name");
        return new Response(Response.SUCCESS, name.textValue());
    }

    /**
     * Returns a {@link Response} object. A {@link Request} is automatically converted by
     * {@link JacksonRequestConverterFunction}.
     *
     * <p>If you want to use a custom {@link ObjectMapper} for JSON converters, you can register a new
     * {@link JacksonRequestConverterFunction} with your custom {@link ObjectMapper} when adding an
     * annotated service as follows:
     * <pre>{@code
     * // Create a new JSON request converter with a custom ObjectMapper.
     * final JacksonRequestConverterFunction requestConverterFunction =
     *         new JacksonRequestConverterFunction(
     *                 new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
     *                                   .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
     * // Register the converter when adding an annotated service to the ServerBuilder.
     * final Server = Server.builder()
     *                      .port(0, SessionProtocol.HTTP)
     *                      .annotatedService("/messageConverter",
     *                                        new MessageConverterService(),
     *                                        requestConverterFunction)
     *                      .build();
     * }</pre>
     */
    @Post("/obj/obj")
    @ProducesJson
    public Response json3(@RequestObject Request request) {
        return new Response(Response.SUCCESS, request.name());
    }

    /**
     * Returns a {@link CompletionStage} object. The {@link JacksonResponseConverterFunction} will
     * be executed after the future is completed with the {@link Response} object.
     *
     * <p>Note that the {@link ServiceRequestContext} of the request is also automatically injected. See
     * <a href="https://armeria.dev/docs/server-annotated-service#other-classes-automatically-injected">
     * Other classes automatically injected</a> for more information.
     */
    @Post("/obj/future")
    @ProducesJson
    public CompletionStage<Response> json4(@RequestObject Request request,
                                           ServiceRequestContext ctx) {
        final CompletableFuture<Response> future = new CompletableFuture<>();
        ctx.blockingTaskExecutor()
           .submit(() -> future.complete(new Response(Response.SUCCESS, request.name())));
        return future;
    }

    /**
     * Returns a {@link Response} object which will be converted to a text by the
     * {@link CustomResponseConverter}. A {@link Request} is also automatically converted by
     * the {@link CustomRequestConverter}.
     *
     * <p>If you want to specify converters for every methods in a service class, you can specify them
     * above the class definition as follows:
     * <pre>{@code
     * > @RequestConverter(CustomRequestConverter.class)
     * > @ResponseConverter(CustomResponseConverter.class)
     * > public class MyAnnotatedService {
     * >     ...
     * > }
     * }</pre>
     */
    @Post("/custom")
    @ProducesText
    @RequestConverter(CustomRequestConverter.class)
    @ResponseConverter(CustomResponseConverter.class)
    public Response custom(@RequestObject Request request) {
        return new Response(Response.SUCCESS, request.name());
    }

    public static final class Request {
        private final String name;

        @JsonCreator
        public Request(@JsonProperty("name") String name) {
            this.name = name;
        }

        @JsonProperty
        public String name() {
            return name;
        }
    }

    public static final class Response {
        static final String SUCCESS = "success";

        private final String result;
        private final String from;

        private Response(String result, String from) {
            this.result = result;
            this.from = from;
        }

        @JsonProperty
        public String result() {
            return result;
        }

        @JsonProperty
        public String from() {
            return from;
        }
    }

    public static final class CustomRequestConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(
                ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

            final MediaType mediaType = request.contentType();
            if (mediaType != null && mediaType.is(MediaType.PLAIN_TEXT_UTF_8)) {
                return new Request(request.contentUtf8());
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static final class CustomResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(
                ServiceRequestContext ctx, ResponseHeaders headers,
                @Nullable Object result, HttpHeaders trailers) throws Exception {

            if (result instanceof Response) {
                final Response response = (Response) result;
                final HttpData body = HttpData.ofUtf8(response.result() + ':' + response.from());
                return HttpResponse.of(headers, body, trailers);
            }
            return ResponseConverterFunction.fallthrough();
        }
    }
}
