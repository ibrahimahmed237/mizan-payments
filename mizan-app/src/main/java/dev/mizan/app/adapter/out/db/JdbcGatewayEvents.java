package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.GatewayEvents;
import dev.mizan.core.payment.Gateway;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The table of webhooks that came in. */
@Repository
class JdbcGatewayEvents implements GatewayEvents {

  private final JdbcClient jdbc;

  JdbcGatewayEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean save(Gateway gateway, String eventId, String type, String gatewayPaymentId, UUID paymentId, Instant now) {
    return jdbc.sql("""
        INSERT INTO gateway_events (gateway, event_id, type, gateway_payment_id, payment_id, received_at) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (gateway, event_id) DO NOTHING
        """)
        .param(gateway.name()).param(eventId).param(type).param(gatewayPaymentId).param(paymentId).param(Sql.time(now))
        .update() == 1;
  }
}
