package dev.mizan.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The rules are plain Java: no framework, no database, no network, no clock of their own. */
@AnalyzeClasses(packages = "dev.mizan.core", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

  @ArchTest
  static final ArchRule the_core_uses_only_the_jdk =
      classes().should().onlyDependOnClassesThat().resideInAnyPackage("dev.mizan.core..", "java..");

  @ArchTest
  static final ArchRule the_core_does_not_read_the_clock =
      noClasses().should().callMethod(java.time.Instant.class, "now")
          .orShould().callMethod(java.time.Clock.class, "systemUTC")
          .orShould().callMethod(System.class, "currentTimeMillis");

  @ArchTest
  static final ArchRule the_core_does_not_talk_to_the_outside =
      noClasses().should().dependOnClassesThat().resideInAnyPackage("java.net..", "java.sql..", "java.nio.file..");
}
