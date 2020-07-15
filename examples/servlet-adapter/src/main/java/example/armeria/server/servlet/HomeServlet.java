package example.armeria.server.servlet;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.MediaType;

public final class HomeServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(HomeServlet.class);
    private static final long serialVersionUID = -439001093551151445L;

    @Override
    public void init(ServletConfig config) throws ServletException {
        logger.info("init ...");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        logger.info("GET: {}", request.getRequestURI());
        try {
            response.setContentType(MediaType.HTML_UTF_8.toString());
            response.addCookie(new Cookie("armeria", "session_id_1"));
            response.getWriter().write(
                    "<html><form style=\"margin: 0 auto; width:350px\" action=\"/app/home?type=servlet\" " +
                    " method=\"post\">\n" +
                    "  <p>AMERIA SERVLET EXAMPLE\n" +
                    "  <p>APPLICATION\n" +
                    "  <p><input type=\"text\" name=\"application\" value=\"Armeria Servlet\">\n" +
                    "  <p><button type=\"submit\" style=\"background:CYAN\">Submit</button>\n" +
                    "</form><html>");
            response.getWriter().close();
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        logger.info("POST: {}", request.getRequestURI());
        try {
            final StringBuilder htmlFile =
                    new StringBuilder("<html><p>SUBMIT DATA SUCCESSFULLY!\n " +
                                      "<p>APPLICATION: " + request.getParameter("application") + "\n" +
                                      "<p>TYPE: " + request.getParameter("type") + "\n" +
                                      "<p>COOKIES:\n"
                    );
            if (request.getCookies() != null) {
                for (Cookie c : request.getCookies()) {
                    htmlFile.append("<p>name: ");
                    htmlFile.append(c.getName());
                    htmlFile.append(" - value: ");
                    htmlFile.append(c.getValue());
                }
            }
            response.setContentType(MediaType.HTML_UTF_8.toString());
            response.getOutputStream().write(htmlFile.toString().getBytes());
            response.getOutputStream().close();
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
