package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.BackofficeQueries;
import dev.mizan.app.application.Problem;
import dev.mizan.app.application.ResolveDiscrepancy;
import dev.mizan.app.application.view.DiscrepancyView;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
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
  Map<String, Object> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    BackofficeQueries.DiscrepancyPage page = queries.discrepancies(status, type, place(cursor), limit);
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("data", page.discrepancies());
    answer.put("next_cursor", page.next() == null ? null : cursor(page.next()));
    return answer;
  }

  /** A cursor is a place in the list, written so that a caller treats it as one opaque word. */
  private static String cursor(long place) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(place).getBytes(StandardCharsets.UTF_8));
  }

  private static Long place(String cursor) {
    if (cursor == null) {
      return null;
    }
    try {
      return Long.valueOf(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw Problem.invalid("invalid_cursor", "The cursor is not one that this API gave.");
    }
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
