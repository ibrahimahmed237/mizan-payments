package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.ApiKeyService;
import dev.mizan.app.application.model.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Checks the caller's key on every path under {@code /v1}, except the two that outsiders call: a gateway's webhook
 * (checked by the gateway's own secret) and the customer's return.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ApiKeyFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";

  private final ApiKeyService keys;
  private final Problems problems;

  ApiKeyFilter(ApiKeyService keys, Problems problems) {
    this.keys = keys;
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/v1/") || path.startsWith("/v1/webhooks/") || path.startsWith("/v1/return/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    Optional<ApiKey> caller = header != null && header.startsWith(BEARER)
        ? keys.find(header.substring(BEARER.length()).strip())
        : Optional.empty();
    if (caller.isEmpty() || caller.get().revokedAt() != null) {
      response.setHeader("WWW-Authenticate", "Bearer");
      problems.write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "A valid API key is needed: Authorization: Bearer <key>.", request);
      return;
    }
    String scope = scopeFor(request.getRequestURI());
    if (!caller.get().allows(scope)) {
      problems.write(response, HttpStatus.FORBIDDEN, "forbidden", "This API key does not have the scope '" + scope + "'.", request);
      return;
    }
    chain.doFilter(request, response);
  }

  /** Payments are for the store's own systems. Everything else under /v1 is for finance. */
  static String scopeFor(String path) {
    return path.equals("/v1/payments") || path.startsWith("/v1/payments/") ? ApiKey.PAYMENTS : ApiKey.BACKOFFICE;
  }
}
