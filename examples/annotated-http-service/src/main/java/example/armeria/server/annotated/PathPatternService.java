package example.armeria.server.annotated;

import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

/**
 * Examples how to use path patterns provided by Armeria.
 *
 * @see <a href="https://armeria.dev/docs/server-annotated-service#mapping-http-service-methods">
 *      Mapping HTTP service methods</a>
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
public class PathPatternService {
    /**
     * Accesses the parameter with the name of the path variable.
     * NOTE: Configure -parameters javac option to use variable name as the parameter name.
     *       (i.e. '@Param String name' instead of '@Param("name") String name')
     */
    @Get("/path/{name}")
    public String pathVar(@Param String name) {
        return "path: " + name;
    }

    /**
     * Accesses the parameter with the name of the capturing group.
     */
    @Get("regex:^/regex/(?<name>.*)$")
    public String regex(@Param String name) {
        return "regex: " + name;
    }

    /**
     * Access the parameter with the index number.
     */
    @Get("glob:/glob/**")
    public String glob(@Param("0") String name) {
        return "glob: " + name;
    }
}
