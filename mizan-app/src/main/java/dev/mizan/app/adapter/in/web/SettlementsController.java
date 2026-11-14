package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.BackofficeQueries;
import dev.mizan.app.application.ImportSettlement;
import dev.mizan.app.application.Problem;
import dev.mizan.app.application.view.SettlementFileView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Finance uploads a gateway's settlement file and follows its matching. */
@RestController
@RequestMapping("/v1/settlements")
class SettlementsController {

  /** A settlement file is at most 50 MB. */
  static final int MAX_FILE = 50 * 1024 * 1024;

  private final ImportSettlement importSettlement;
  private final BackofficeQueries queries;

  SettlementsController(ImportSettlement importSettlement, BackofficeQueries queries) {
    this.importSettlement = importSettlement;
    this.queries = queries;
  }

  /** The body is the file itself, as text. The same file again is answered as the first time. */
  @PostMapping
  ResponseEntity<SettlementFileView> upload(HttpServletRequest request) throws IOException {
    byte[] content = request.getInputStream().readNBytes(MAX_FILE + 1);
    if (content.length > MAX_FILE) {
      throw new Problem(Problem.Kind.TOO_LARGE, "file_too_large", "A settlement file is at most 50 MB.");
    }
    var result = importSettlement.handle(content);
    var view = queries.file(result.file().id());
    return result.replay()
        ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(view)
        : ResponseEntity.status(HttpStatus.CREATED).body(view);
  }

  @GetMapping
  Map<String, List<SettlementFileView>> list() {
    return Map.of("data", queries.files());
  }

  @GetMapping("/{id}")
  SettlementFileView read(@PathVariable UUID id) {
    return queries.file(id);
  }
}
