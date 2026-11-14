package dev.mizan.app.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The limits of docs/lld/running.md: requests per second, and the size of a body. They are counted per instance of
 * Mizan. A request over a limit is refused before any work is done.
 *
 * <ul>
 *   <li>The API: per API key, after the key was checked.
 *   <li>Webhooks: per gateway.
 *   <li>The customer's return: per address, so a stranger cannot flood the gateway through it.
 * </ul>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class LimitsFilter extends OncePerRequestFilter {

  /** The largest body of an API request or a webhook. Settlement files have their own limit. */
  static final long MAX_BODY = 64 * 1024;

  /** Requests per second and the burst a caller may have at once. */
  public record Limit(double perSecond, int burst) {}

  private final RateLimiter api;
  private final RateLimiter webhooks;
  private final RateLimiter returns;
  private final Problems problems;

  public LimitsFilter(Limit api, Limit webhooks, Limit returns, Problems problems) {
    this.api = new RateLimiter(api.perSecond(), api.burst());
    this.webhooks = new RateLimiter(webhooks.perSecond(), webhooks.burst());
    this.returns = new RateLimiter(returns.perSecond(), returns.burst());
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    boolean settlement = path.equals("/v1/settlements");
    if (!settlement && request.getContentLengthLong() > MAX_BODY) {
      problems.write(response, HttpStatus.CONTENT_TOO_LARGE, "body_too_large", "A request body is at most 64 KB.", request);
      return;
    }
    long waitSeconds;
    if (path.startsWith("/v1/webhooks/")) {
      waitSeconds = webhooks.take(path);
    } else if (path.startsWith("/v1/return/")) {
      waitSeconds = returns.take(request.getRemoteAddr());
    } else {
      waitSeconds = api.take(String.valueOf(request.getAttribute(ApiKeyFilter.CALLER)));
    }
    if (waitSeconds > 0) {
      response.setHeader("Retry-After", Long.toString(waitSeconds));
      problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many requests. Try again after Retry-After seconds.", request);
      return;
    }
    if (!settlement && "chunked".equalsIgnoreCase(request.getHeader("Transfer-Encoding"))) {
      // A body sent in pieces does not say its size. It is read here, up to the limit, before anything else reads it.
      byte[] body = request.getInputStream().readNBytes((int) MAX_BODY + 1);
      if (body.length > MAX_BODY) {
        problems.write(response, HttpStatus.CONTENT_TOO_LARGE, "body_too_large", "A request body is at most 64 KB.", request);
        return;
      }
      chain.doFilter(new ReadBody(request, body), response);
      return;
    }
    chain.doFilter(request, response);
  }

  /** The request, with a body that was read already. */
  private static final class ReadBody extends HttpServletRequestWrapper {
    private final byte[] body;

    ReadBody(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return in.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("The body was read already.");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
