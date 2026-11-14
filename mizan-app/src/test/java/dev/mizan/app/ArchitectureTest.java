package dev.mizan.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layers of the service. See CONTRIBUTING.md, "Code structure". */
@AnalyzeClasses(packages = "dev.mizan.app", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** The use cases know the rules and their own ports. They do not know HTTP, SQL or a gateway's wire. */
  @ArchTest
  static final ArchRule the_application_does_not_know_its_adapters =
      noClasses().that().resideInAPackage("..application..")
          .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..");

  /** From Spring, a use case may use only {@code @Service}. Transactions go through the Transactions port. */
  @ArchTest
  static final ArchRule the_application_uses_nothing_of_spring_but_the_service_annotation =
      noClasses().that().resideInAPackage("..application..")
          .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
              "are Spring, Jackson, JDBC or servlet classes, other than @Service",
              type -> (type.getPackageName().startsWith("org.springframework") && !type.getName().equals("org.springframework.stereotype.Service"))
                  || type.getPackageName().startsWith("tools.jackson")
                  || type.getPackageName().startsWith("java.sql")
                  || type.getPackageName().startsWith("jakarta.")
                  || type.getPackageName().startsWith("java.net.http")));

  /** An adapter that comes in (HTTP, the command line) does not reach around the use cases into the database or a gateway. */
  @ArchTest
  static final ArchRule incoming_adapters_do_not_use_outgoing_adapters =
      noClasses().that().resideInAPackage("..adapter.in..")
          .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

  @ArchTest
  static final ArchRule outgoing_adapters_do_not_use_incoming_adapters_or_each_other =
      noClasses().that().resideInAPackage("..adapter.out.db..")
          .should().dependOnClassesThat().resideInAnyPackage("..adapter.in..", "..adapter.out.gateway..");

  @ArchTest
  static final ArchRule the_gateway_clients_know_no_database_and_no_http_api =
      noClasses().that().resideInAPackage("..adapter.out.gateway..")
          .should().dependOnClassesThat().resideInAnyPackage("..adapter.in..", "..adapter.out.db..", "java.sql..", "org.springframework..");
}
