package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.EventQueries;
import dev.mizan.app.application.view.EventView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finance follows the events sent to the store, and sends one again. */
@RestController
@RequestMapping("/v1/events")
class EventsController {

  private final EventQueries events;

  EventsController(EventQueries events) {
    this.events = events;
  }

  @GetMapping
  Map<String, List<EventView>> list(@RequestParam(required = false) String status, @RequestParam(name = "payment_id", required = false) UUID paymentId) {
    return Map.of("data", events.list(status, paymentId));
  }

  @PostMapping("/{id}/redeliver")
  EventView redeliver(@PathVariable UUID id) {
    return events.redeliver(id);
  }
}
