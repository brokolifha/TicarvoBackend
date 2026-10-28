package com.ticarvo.config;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Binder;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
class ConfigurationPropertiesTest {
 private ApplicationContextRunner runner() {
  return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
   .withPropertyValues("DB_URL=jdbc:mysql://127.0.0.1:56128/synthetic_config_only","DB_USERNAME=synthetic","DB_PASSWORD=synthetic-placeholder",
    "PORT=18088","CORS_ALLOWED_ORIGINS=https://example.invalid");
 }
 @Test void defaultSettingsAndRequiredPlaceholdersArePreserved() throws Exception {
  var loader=getClass().getClassLoader();
  assertNull(loader.getResource("application.yaml"));assertNull(loader.getResource("application-local.yaml"));
  Properties raw=new Properties();try(var input=loader.getResourceAsStream("application.properties")){assertNotNull(input);raw.load(input);}
  assertEquals(34,raw.size());assertEquals("${PUBLIC_BASE_URL:http://localhost:5173}",raw.getProperty("ticarvo.public-base-url"));assertEquals("${COMMISSION_RATE:0}",raw.getProperty("ticarvo.commission.default-rate"));assertEquals("${SELLER_VERIFICATION_REQUIRED:true}",raw.getProperty("ticarvo.sellers.verification-required"),"production default: admin-verified sellers (ADR-011)");
  assertEquals("${DB_URL}",raw.getProperty("spring.datasource.url"));
  assertEquals("${DB_USERNAME}",raw.getProperty("spring.datasource.username"));
  assertEquals("${DB_PASSWORD}",raw.getProperty("spring.datasource.password"));
  runner().run(context->{
   var env=context.getEnvironment();
   assertEquals("jdbc:mysql://127.0.0.1:56128/synthetic_config_only",env.getProperty("spring.datasource.url"));
   assertEquals("18088",env.getProperty("server.port"));
   assertEquals("https://example.invalid",env.getProperty("ticarvo.cors.allowed-origins"));
   String[] expected={
    "spring.application.name=ticarvo-backend","spring.jpa.open-in-view=false","spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC","spring.flyway.enabled=true","spring.flyway.clean-disabled=true",
    "spring.session.timeout=30m","spring.session.jdbc.initialize-schema=never","spring.jackson.deserialization.fail-on-unknown-properties=true",
    "spring.mvc.problemdetails.enabled=true","server.forward-headers-strategy=native","server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1",
    "server.servlet.session.cookie.name=TICARVO_SESSION","server.servlet.session.cookie.path=/",
    "server.servlet.session.cookie.http-only=true","server.servlet.session.cookie.secure=true","server.servlet.session.cookie.same-site=lax",
    "server.error.include-message=never","server.error.include-stacktrace=never","server.error.include-binding-errors=never",
    "server.tomcat.max-http-form-post-size=16KB","management.endpoints.web.exposure.include=health",
    "management.endpoint.health.show-details=never","management.endpoint.health.show-components=never",
    "spring.servlet.multipart.max-file-size=5MB","spring.servlet.multipart.max-request-size=6MB"};
   for(String entry:expected){String[] pair=entry.split("=",2);assertEquals(pair[1],env.getProperty(pair[0]),pair[0]);}
   assertTrue(Binder.get(env).bind("server.servlet.session.cookie.secure",Boolean.class).orElseThrow(IllegalStateException::new));
   assertFalse(java.util.Arrays.asList(env.getActiveProfiles()).contains("local"));
  });
 }
 @Test void onlyExplicitLocalProfileDisablesSecureCookie() {
  runner().withPropertyValues("spring.profiles.active=local").run(context->{
   assertFalse(Binder.get(context.getEnvironment()).bind("server.servlet.session.cookie.secure",Boolean.class).orElseThrow(IllegalStateException::new));
   assertEquals("true",context.getEnvironment().getProperty("server.servlet.session.cookie.http-only"));
  });
 }
}
