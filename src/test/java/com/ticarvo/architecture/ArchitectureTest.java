package com.ticarvo.architecture;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.core.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {
 private static final Set<String> MODULES=Set.of("identity","company","trade","catalog","admin","order");
 private static final Set<String> BOUNDARIES=Set.of("com.ticarvo.identity.service.CurrentUserProvider",
  "com.ticarvo.company.service.CompanyAccess","com.ticarvo.company.dto.CompanySummary","com.ticarvo.trade.service.TradeAccess",
  "com.ticarvo.identity.service.UserDirectory","com.ticarvo.identity.dto.UserSummary",
  // ADR-008 admin module boundaries.
  "com.ticarvo.identity.service.AccountModeration","com.ticarvo.identity.dto.AccountView","com.ticarvo.company.service.CompanyModeration",
  "com.ticarvo.company.dto.CompanyAdminViews","com.ticarvo.company.dto.CompanyAdminViews$Company","com.ticarvo.company.dto.CompanyAdminViews$Membership",
  "com.ticarvo.catalog.service.CatalogLookup",
  // ADR-009 order module boundaries.
  "com.ticarvo.catalog.service.CatalogOrdering","com.ticarvo.catalog.dto.OrderableProduct",
  // ADR-011 shipping terms read by the order module.
  "com.ticarvo.company.dto.ShippingTerms",
  // ADR-011 company profile seen by the admin module.
  "com.ticarvo.company.dto.ProfileDtos$Profile","com.ticarvo.company.dto.ProfileDtos$Billing","com.ticarvo.company.model.SellerStatus");
 private static String module(String name){String[] parts=name.split("\\.");return parts.length>2?parts[2]:"";}
 private static boolean layer(String name,String layer){return name.contains("."+layer+".");}
 @Test void productionLayersAndModuleBoundariesAreEnforced() {
  JavaClasses classes=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.ticarvo");
  assertTrue(classes.size()>50,"Production import must include the implemented modules");
  List<String> violations=new ArrayList<>();
  for(JavaClass source:classes) {
   String from=source.getName(), mod=module(from);
   if(MODULES.contains(mod) && source.getPackageName().equals("com.ticarvo."+mod))
    violations.add("Flat domain class: "+from);
   if(source.isAnnotatedWith("org.springframework.web.bind.annotation.RestController") && !layer(from,"controller")) violations.add("Controller placement: "+from);
   if(source.isAnnotatedWith("org.springframework.stereotype.Service") && !layer(from,"service")) violations.add("Service placement: "+from);
   if(source.isAnnotatedWith("jakarta.persistence.Entity") && !layer(from,"entity")) violations.add("Entity placement: "+from);
   if(source.getAllRawInterfaces().stream().anyMatch(i->i.getName().equals("org.springframework.data.jpa.repository.JpaRepository")) && !layer(from,"repository")) violations.add("Repository placement: "+from);
   for(Dependency dep:source.getDirectDependenciesFromSelf()) {
    String to=dep.getTargetClass().getName(), target=module(to);
    boolean internal=to.startsWith("com.ticarvo.");
    boolean invalid=layer(from,"controller") && (layer(to,"entity")||layer(to,"repository")||to.equals("jakarta.persistence.EntityManager"));
    invalid |= layer(from,"service") && internal && layer(to,"controller");
    invalid |= layer(from,"entity") && internal && (layer(to,"dto")||layer(to,"controller")||layer(to,"service")||layer(to,"repository"));
    invalid |= layer(from,"dto") && internal && (layer(to,"entity")||layer(to,"repository")||layer(to,"service")||layer(to,"controller"));
    invalid |= internal && layer(to,"repository") && (!mod.equals(target)||!(layer(from,"service")||layer(from,"repository")));
    invalid |= MODULES.contains(mod) && MODULES.contains(target) && !mod.equals(target) && !BOUNDARIES.contains(to);
    invalid |= mod.equals("shared") && MODULES.contains(target);
    if(invalid) violations.add(dep.getDescription());
   }
  }
  assertEquals(List.of(),violations);
  slices().matching("com.ticarvo.(*)..").should().beFreeOfCycles().check(classes);
 }
 @Test void controllerRepositoryViolationIsDetected() {
  var fixture=new ClassFileImporter().importClasses(com.ticarvo.architecture.fixture.controller.BadController.class);
  var rule=noClasses().that().resideInAPackage("..controller..").should().dependOnClassesThat().resideInAPackage("..repository..");
  assertTrue(rule.evaluate(fixture).hasViolation(),"Architecture rule must detect a deliberately invalid test fixture");
 }
}
