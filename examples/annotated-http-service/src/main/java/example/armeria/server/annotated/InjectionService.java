package example.armeria.server.annotated;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

/**
 * Examples how to use {@link Param}, {@link Header} and {@link Cookies}.
 *
 * @see <a href="https://armeria.dev/docs/server-annotated-service#parameter-injection">
 *      Parameter injection</a>
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
public class InjectionService {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns the received {@code name}, {@code id} and {@link Gender} to the sender as a JSON list.
     */
    @Get("/param/{name}/{id}")
    public HttpResponse param(@Param String name,  /* from path variable */
                              @Param int id,       /* from path variable and converted into integer*/
                              @Param Gender gender /* from query string and converted into enum */)
            throws JsonProcessingException {
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                               mapper.writeValueAsBytes(Arrays.asList(name, id, gender)));
    }

    /**
     * Returns the received {@code x-armeria-text}, {@code x-armeria-sequence}, {@code Cookie} headers
     * to the sender as a JSON list.
     */
    @Get("/header")
    public HttpResponse header(@Header String xArmeriaText,            /* no conversion */
                               @Header List<Integer> xArmeriaSequence, /* converted into integer */
                               Cookies cookies                         /* converted into Cookies object */)
            throws JsonProcessingException {
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, mapper.writeValueAsBytes(
                Arrays.asList(xArmeriaText,
                              xArmeriaSequence,
                              cookies.stream().map(Cookie::name).collect(Collectors.toList()))));
    }

    /**
     * A sample {@link Enum} for the automatic conversion example. The elements have unique names in a
     * case-insensitive way, so they can be converted in a case-insensitive way.
     */
    enum Gender {
        MALE,
        FEMALE
    }
}
