package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.BackofficeQueries;
import dev.mizan.app.application.Problem;
import dev.mizan.app.application.ResolveDiscrepancy;
import dev.mizan.app.application.view.DiscrepancyView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finance reads the differences and closes them. */
@RestController
@RequestMapping("/v1/discrepancies")
class DiscrepanciesController {

  /** The body of "accept". */
  record AcceptBody(String note) {}

  private final ResolveDiscrepancy resolve;
  private final BackofficeQueries queries;

  DiscrepanciesController(ResolveDiscrepancy resolve, BackofficeQueries queries) {
    this.resolve = resolve;
    this.queries = queries;
  }

  @GetMapping
  Map<String, List<DiscrepancyView>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String type) {
    return Map.of("data", queries.discrepancies(status, type));
  }

  @GetMapping("/{id}")
  DiscrepancyView read(@PathVariable UUID id) {
    return queries.discrepancy(id);
  }

  @PostMapping("/{id}/retry")
  DiscrepancyView retry(@PathVariable UUID id) {
    return DiscrepancyView.of(resolve.retry(id));
  }

  @PostMapping("/{id}/accept")
  DiscrepancyView accept(@PathVariable UUID id, @RequestBody(required = false) AcceptBody body) {
    if (body == null) {
      throw Problem.invalid("note_required", "Accepting a difference needs a note that says why.");
    }
    return DiscrepancyView.of(resolve.accept(id, body.note()));
  }
}
