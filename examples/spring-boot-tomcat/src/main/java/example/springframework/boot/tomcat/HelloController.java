package example.springframework.boot.tomcat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @RequestMapping(method = RequestMethod.GET, path = "/")
    String index() {
        return "index";
    }

    @RequestMapping(method = RequestMethod.GET, path = "/hello")
    String hello() {
        return "Hello, World";
    }
}
