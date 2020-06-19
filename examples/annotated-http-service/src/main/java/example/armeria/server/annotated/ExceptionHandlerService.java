package example.armeria.server.annotated;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import example.armeria.server.annotated.ExceptionHandlerService.GlobalExceptionHandler;

/**
 * Examples how to use {@link ExceptionHandler}.
 *
 * @see <a href="https://armeria.dev/docs/server-annotated-service#handling-exceptions">
 *      Handling exceptions</a>
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
@ExceptionHandler(GlobalExceptionHandler.class)
public class ExceptionHandlerService {

    /**
     * Throws a {@link LocallySpecificException} which can be handled by the {@link LocalExceptionHandler}.
     */
    @Get("/locallySpecific")
    @ExceptionHandler(LocalExceptionHandler.class)
    public String exception1() {
        throw new LocallySpecificException();
    }

    /**
     * Throws a {@link LocallyGeneralException} which can be handled by the {@link LocalExceptionHandler}.
     */
    @Get("/locallyGeneral")
    @ExceptionHandler(LocalExceptionHandler.class)
    public String exception2() {
        throw new LocallyGeneralException();
    }

    /**
     * Throws a {@link GloballyGeneralException} which can be handled by the {@link GlobalExceptionHandler}.
     */
    @Get("/globallyGeneral")
    @ExceptionHandler(LocalExceptionHandler.class)
    public String exception3() {
        throw new GloballyGeneralException();
    }

    /**
     * Throws an {@link IllegalArgumentException} which can be handled by the default exception handler.
     */
    @Get("/default")
    @ExceptionHandler(LocalExceptionHandler.class)
    public String exception4() {
        throw new IllegalArgumentException();
    }

    /**
     * Throws an {@link HttpStatusException} which can be handled by the default exception handler.
     * The {@code status code} of the exception is from the path variable of {@code status}.
     */
    @Get("/default/{status}")
    @ExceptionHandler(LocalExceptionHandler.class)
    public String exception5(@Param int status) {
        throw HttpStatusException.of(status);
    }

    static class GloballyGeneralException extends RuntimeException {
        private static final long serialVersionUID = 8210080483318166316L;
    }

    static class LocallyGeneralException extends GloballyGeneralException {
        private static final long serialVersionUID = -9167203216151475846L;
    }

    static class LocallySpecificException extends LocallyGeneralException {
        private static final long serialVersionUID = 5879797158322612975L;
    }

    static final class GlobalExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof GloballyGeneralException) {
                return HttpResponse.of(HttpStatus.FORBIDDEN);
            }
            // To the next exception handler.
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    static final class LocalExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof LocallySpecificException) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
            }
            if (cause instanceof LocallyGeneralException) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST);
            }
            // To the next exception handler.
            return ExceptionHandlerFunction.fallthrough();
        }
    }
}
