package dev.mizan.app.adapter.in.web;

import dev.mizan.app.application.CustomerReturned;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a gateway sends the customer after the bank's page or the provider's page. No API key: a browser comes
 * here. Everything the gateway added to the address is ignored: it passed through the customer's hands. The page
 * shows nothing; it only sends the customer on to the address the store gave when it made the payment.
 */
@RestController
class ReturnController {

  private final CustomerReturned returned;

  ReturnController(CustomerReturned returned) {
    this.returned = returned;
  }

  @GetMapping("/v1/return/{id}")
  ResponseEntity<Void> back(@PathVariable UUID id) {
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(returned.handle(id))).build();
  }
}
