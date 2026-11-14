package dev.mizan.app.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Gives every request an id, for the logs and for error answers, and sets the headers every answer has. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestFilter extends OncePerRequestFilter {

  static final String REQUEST_ID = "request_id";
  private static final String HEADER = "X-Request-Id";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String given = request.getHeader(HEADER);
    // A caller's id is used only when it is safe to write into a log line.
    String id = given != null && SAFE.matcher(given).matches() ? given : UUID.randomUUID().toString();
    request.setAttribute(REQUEST_ID, id);
    response.setHeader(HEADER, id);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Strict-Transport-Security", "max-age=31536000");
    MDC.put(REQUEST_ID, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID);
    }
  }
}
