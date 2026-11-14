package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.Discrepancies;
import dev.mizan.core.payment.Gateway;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The discrepancies table. A partial unique index keeps one open discrepancy of a type per subject. */
@Repository
class JdbcDiscrepancies implements Discrepancies {

  private final JdbcClient jdbc;

  JdbcDiscrepancies(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> open(String type, UUID paymentId, Long expected, Long actual, Instant at) {
    UUID id = UUID.randomUUID();
    int rows = jdbc.sql("""
        INSERT INTO discrepancies (id, type, payment_id, expected, actual, created_at) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (payment_id, type) WHERE status = 'OPEN' DO NOTHING
        """)
        .param(id).param(type).param(paymentId).param(expected).param(actual).param(Sql.time(at))
        .update();
    return rows == 1 ? Optional.of(id) : Optional.empty();
  }

  @Override
  public Optional<UUID> openForGatewayPayment(String type, Gateway gateway, String gatewayPaymentId, Instant at) {
    UUID id = UUID.randomUUID();
    int rows = jdbc.sql("""
        INSERT INTO discrepancies (id, type, gateway, gateway_payment_id, created_at) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (gateway, gateway_payment_id, type) WHERE status = 'OPEN' AND payment_id IS NULL DO NOTHING
        """)
        .param(id).param(type).param(gateway.name()).param(gatewayPaymentId).param(Sql.time(at))
        .update();
    return rows == 1 ? Optional.of(id) : Optional.empty();
  }
}
