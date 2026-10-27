package com.ticarvo.shared.security;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.web.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class CatalogRateLimitTest {
 @Test void catalogPrincipalOperationsHaveIndependentGlobalBudgets(){
  var limiter=new PrincipalRateLimit(Clock.systemUTC(),900,10000,60,10,30,10,30,120,300,10,30);
  UUID user=UUID.randomUUID();
  var operations=java.util.Map.of(PrincipalRateLimit.Operation.PRODUCT_CREATE,30,PrincipalRateLimit.Operation.PRODUCT_MUTATE,120,PrincipalRateLimit.Operation.CATALOG_READ,300);
  operations.forEach((operation,limit)->{
   for(int i=0;i<limit;i++)limiter.check(operation,user);
   assertEquals("RATE_LIMITED",assertThrows(ApiException.class,()->limiter.check(operation,user)).code());
   assertDoesNotThrow(()->limiter.check(operation,UUID.randomUUID()));
  });
 }
 @Test void publicQuotaIsSharedAcrossProductPathsAndIgnoresForwardedIdentity() throws Exception {
  var filter=new RequestProtectionFilter(new ProblemWriter(JsonMapper.builder().build()));
  for(int i=0;i<=120;i++){
   var request=new MockHttpServletRequest("GET","/api/v1/catalog/products/"+UUID.randomUUID());
   request.setServletPath(request.getRequestURI());request.setRemoteAddr("192.0.2.1");
   request.addHeader("X-Forwarded-For","198.51.100."+i);
   var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{});
   assertEquals(i<120?200:429,response.getStatus());
   if(i==120)assertEquals("900",response.getHeader("Retry-After"));
  }
  var different=new MockHttpServletRequest("GET","/api/v1/catalog/products");different.setServletPath(different.getRequestURI());different.setRemoteAddr("192.0.2.2");
  var response=new MockHttpServletResponse();filter.doFilter(different,response,(req,res)->{});assertEquals(200,response.getStatus());
 }
 /** SEC-M3-001: anonymous catalog keys from many addresses must not fill the login/register key space. */
 @Test void publicCatalogKeysCannotExhaustLoginAndRegisterCapacity() throws Exception {
  var filter=new RequestProtectionFilter(new ProblemWriter(JsonMapper.builder().build()));
  for(int i=0;i<10000;i++)assertEquals(200,send(filter,"GET","/api/v1/catalog/products","10.0."+(i/256)+"."+(i%256)));
  assertEquals(429,send(filter,"GET","/api/v1/catalog/products","203.0.113.9"),"Full public capacity fails closed for new public keys");
  assertEquals(200,send(filter,"POST","/api/v1/auth/login","203.0.113.10"),"Login must not inherit public catalog exhaustion");
  assertEquals(200,send(filter,"POST","/api/v1/auth/register","203.0.113.11"),"Register must not inherit public catalog exhaustion");
 }
 private static int send(RequestProtectionFilter filter,String method,String path,String address) throws Exception {
  var request=new MockHttpServletRequest(method,path);request.setServletPath(path);request.setRemoteAddr(address);
  var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{});return response.getStatus();
 }
}
