package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** Turns every failure into the one error shape. Nothing of Mizan's inside is shown to the caller. */
@RestControllerAdvice
class ProblemAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

  private final Problems problems;

  ProblemAdvice(Problems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(Problem.class)
  ResponseEntity<Map<String, Object>> problem(Problem problem, HttpServletRequest request) {
    HttpStatus status = switch (problem.kind()) {
      case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
      case CONFLICT -> HttpStatus.CONFLICT;
      case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
      case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
    };
    return problems.answer(status, problem.code(), problem.getMessage(), request);
  }

  /** A body that is not JSON, has a field Mizan does not know, or has a value of the wrong kind. */
  @ExceptionHandler({HttpMessageNotReadableException.class, JacksonException.class})
  ResponseEntity<Map<String, Object>> unreadable(Exception e, HttpServletRequest request) {
    Throwable cause = e instanceof HttpMessageNotReadableException ? e.getCause() : e;
    String title = "The body cannot be read as JSON.";
    if (cause instanceof UnrecognizedPropertyException unknown) {
      title = "The body has a field that is not known: " + unknown.getPropertyName() + ".";
    } else if (cause instanceof DatabindException wrong && !wrong.getPath().isEmpty()) {
      String field = wrong.getPath().stream()
          .map(step -> step.getPropertyName() == null ? "[" + step.getIndex() + "]" : step.getPropertyName())
          .collect(Collectors.joining("."));
      title = "The field " + field + " has a value of the wrong kind.";
    }
    return problems.answer(HttpStatus.BAD_REQUEST, "malformed_body", title, request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e, HttpServletRequest request) {
    return problems.answer(HttpStatus.NOT_FOUND, "not_found", "There is nothing at this address.", request);
  }

  /** An id in the path that is not a UUID names nothing. Any other value of the wrong kind is the caller's mistake. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> wrongKind(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
    if (e.getParameter().hasParameterAnnotation(PathVariable.class)) {
      return problems.answer(HttpStatus.NOT_FOUND, "not_found", "There is nothing at this address.", request);
    }
    return problems.answer(HttpStatus.BAD_REQUEST, "invalid_parameter", "The parameter " + e.getName() + " has a value of the wrong kind.", request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
    return problems.answer(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "This address does not take " + e.getMethod() + ".", request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
    return problems.answer(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "The body must be application/json.", request);
  }

  /** Mizan's own fault. The caller learns only that, and the id to quote; the log has the rest. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
    LOG.error("Unexpected failure on {} {}", request.getMethod(), request.getRequestURI(), e);
    return problems.answer(
        HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Mizan failed. It is safe to send the request again with the same Idempotency-Key.", request);
  }
}
