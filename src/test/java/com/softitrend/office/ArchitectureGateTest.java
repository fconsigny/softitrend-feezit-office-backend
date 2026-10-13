package com.softitrend.office;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
class ArchitectureGateTest {
 @Test void domainAndApplicationHaveNoOutwardDependencies() {
  var classes=new ClassFileImporter().importPackages("com.softitrend.office");
  noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
   .resideInAnyPackage("org.springframework..","reactor..","..adapters..","..infrastructure..","..application..").check(classes);
  noClasses().that().resideInAPackage("..application..").should().dependOnClassesThat()
   .resideInAnyPackage("..adapters..","..infrastructure..").check(classes);
 }
}
