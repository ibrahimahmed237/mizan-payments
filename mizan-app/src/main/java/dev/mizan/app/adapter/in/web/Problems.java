package dev.mizan.app.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Every error has one shape: {@code application/problem+json} with a code a program can read. */
@Component
public class Problems {

  private final JsonMapper json;

  Problems(JsonMapper json) {
    this.json = json;
  }

  Map<String, Object> body(HttpStatus status, String code, String title, HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://mizan.example/problems/" + code);
    body.put("title", title);
    body.put("status", status.value());
    body.put("code", code);
    body.put("request_id", request.getAttribute(RequestFilter.REQUEST_ID));
    return body;
  }

  ResponseEntity<Map<String, Object>> answer(HttpStatus status, String code, String title, HttpServletRequest request) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body(status, code, title, request));
  }

  /** For a filter, which answers before any controller runs. */
  void write(HttpServletResponse response, HttpStatus status, String code, String title, HttpServletRequest request) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(body(status, code, title, request)));
  }
}
