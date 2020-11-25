package example.springframework.boot.tomcat;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@WebMvcTest(HelloController.class)
class HelloControllerTest {
    @Inject
    private MockMvc mvc;

    @Test
    void index() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/"))
           .andExpect(status().isOk())
           .andExpect(content().string("index"));
    }

    @Test
    void hello() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/hello"))
           .andExpect(status().isOk())
           .andExpect(content().string("Hello, World"));
    }
}
