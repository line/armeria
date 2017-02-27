<%@ page import="java.io.BufferedReader" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.io.InputStreamReader" %>
<%@ page import="java.util.stream.Collectors" %>
<%@ page contentType="text/html; charset=UTF-8" %>
<%
    InputStream is = request.getInputStream();
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
%>
<html>
<body>
<p>Check request body</p>
<p><%= br.lines().collect(Collectors.joining(System.lineSeparator())) %></p>
</body>
</html>
