package dev.mizan.app.adapter.out.db;

import dev.mizan.app.application.port.Transactions;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs work in one database transaction. A transaction inside another joins it. */
@Component
class SpringTransactions implements Transactions {

  private final TransactionTemplate template;

  SpringTransactions(PlatformTransactionManager manager) {
    this.template = new TransactionTemplate(manager);
  }

  @Override
  public <T> T run(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
