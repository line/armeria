package example.armeria.server.saml.sp;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;

final class MyService {
    @Get("/welcome")
    public HttpResponse welcome(Cookies cookies) {
        final String name = cookies.stream().filter(c -> "username".equals(c.name()))
                                   .map(Cookie::value).findFirst()
                                   .orElseThrow(() -> new IllegalArgumentException("No username is found."));
        return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8,
                               "<html><body>Hello, %s! You can see this message " +
                               "because you've been authenticated by SSOCircle.</body></html>",
                               name);
    }
}
