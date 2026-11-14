package dev.mizan.app.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the API's description (OpenAPI 3.1). It is written by hand, next to the code, and a test keeps the two
 * the same: every address the service answers is described, and every described address is answered.
 */
@RestController
class ApiDescriptionController {

  private final String description;

  ApiDescriptionController() throws IOException {
    this.description = new ClassPathResource("openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
  }

  @GetMapping("/v3/api-docs")
  ResponseEntity<String> description() {
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(description);
  }
}
