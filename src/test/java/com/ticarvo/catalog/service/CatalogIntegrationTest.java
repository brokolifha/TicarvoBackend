package com.ticarvo.catalog.service;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.entity.ProductEvent;
import com.ticarvo.catalog.model.ProductStatus;
import com.ticarvo.catalog.repository.ProductEventRepository;
import com.ticarvo.company.service.*;
import com.ticarvo.identity.service.IdentityService;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.trade.service.*;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.model.TradeStatus;
import com.ticarvo.shared.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;import java.time.Duration;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class CatalogIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",()->required("TEST_DB_URL"));r.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
 }
 static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException(key+" required");return value;}
 @Autowired IdentityService identity;@Autowired CompanyService companies;@Autowired SellerDirectoryService sellers;
 @Autowired TradeService trade;@Autowired CatalogService catalog;@Autowired PublicCatalogService publicCatalog;@Autowired InvitationService invitations;
 @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager transactions;
 @MockitoSpyBean CompanyAccessService access;@MockitoSpyBean ProductEventRepository events;
 @Value("${local.server.port}") int port;
 static final String PASSWORD="Synthetic-M3-Password!95";
 record Actor(UUID user,String email,Authentication auth,UUID company){}
 record Setup(Actor seller,Actor buyer,UUID relation,CatalogDtos.Owner product){}
 Actor actor(){
  var u=identity.register(new RegisterRequest("m3-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M3"));
  jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",u.id().toString());
  var auth=new UsernamePasswordAuthenticationToken(u.email(),"",List.of());
  return new Actor(u.id(),u.email(),auth,companies.create("Synthetic M3 "+UUID.randomUUID(),auth).id());
 }
 CatalogDtos.PriceInput price(String amount,String currency){return new CatalogDtos.PriceInput(json.readTree("\""+amount+"\""),currency,json.readTree("false"));}
 CatalogDtos.Create input(String sku){return new CatalogDtos.Create(sku,"Synthetic product","Plain <script>text</script>",price("125.50","TRY"),null,null,null);}
 CatalogDtos.Owner create(Actor s,String sku){return catalog.create(s.company(),input(sku),s.auth());}
 UUID approve(Actor b,Actor s){
  sellers.update(s.company(),true,s.auth());var r=trade.apply(b.company(),new TradeDtos.Apply(s.company(),null),b.auth());
  return trade.decide(s.company(),r.id(),0,TradeStatus.APPROVED,s.auth()).id();
 }
 Setup setup(){
  var s=actor();var b=actor();UUID relation=approve(b,s);var p=create(s,"sku");
  var g=catalog.replaceBuyers(s.company(),p.id(),new CatalogDtos.BuyersInput(0L,List.of(b.company())),s.auth());
  p=catalog.transition(s.company(),p.id(),g.productVersion(),ProductStatus.PUBLISHED,s.auth());return new Setup(s,b,relation,p);
 }
 void error(String code,Runnable action){assertEquals(code,assertThrows(ApiException.class,action::run).code());}
 @Test void tenantDirectionGrantsRoleAndLifecycle(){
  var x=setup();var s=x.seller();var b=x.buyer();var p=x.product();var other=actor();approve(other,s);
  assertEquals("SKU",p.sku());assertFalse(p.publicVisible());
  UUID absentSeller=UUID.randomUUID();
  error("PRODUCT_NOT_FOUND",()->catalog.buyerGet(b.company(),absentSeller,p.id(),b.auth()));
  error("PRODUCT_NOT_FOUND",()->catalog.buyerList(b.company(),absentSeller,null,null,null,0,20,b.auth()));
  error("PRODUCT_NOT_FOUND",()->catalog.buyerGet(b.company(),other.company(),p.id(),b.auth()));
  error("PRODUCT_NOT_FOUND",()->catalog.buyerList(b.company(),other.company(),null,null,null,0,20,b.auth()));
  assertEquals("125.50",catalog.buyerGet(b.company(),s.company(),p.id(),b.auth()).price().amount());
  assertEquals(0,catalog.buyerList(other.company(),s.company(),null,null,null,0,20,other.auth()).totalElements());
  error("PRODUCT_NOT_FOUND",()->catalog.buyerGet(other.company(),s.company(),p.id(),other.auth()));
  error("COMPANY_NOT_FOUND",()->catalog.buyerGet(b.company(),s.company(),p.id(),other.auth()));
  error("PRODUCT_NOT_FOUND",()->catalog.buyerGet(s.company(),b.company(),p.id(),s.auth()));
  error("PRODUCT_NOT_FOUND",()->catalog.get(other.company(),p.id(),other.auth()));
  error("PRODUCT_STATE_CONFLICT",()->catalog.edit(s.company(),p.id(),new CatalogDtos.Edit(p.version(),"NEW","Name","",price("0","USD"),null,null,null),s.auth()));
  var second=companies.create("Same user other buyer",b.auth()).id();approve(new Actor(b.user(),b.email(),b.auth(),second),s);
  assertEquals(0,catalog.buyerList(second,s.company(),null,null,null,0,20,b.auth()).totalElements());
  // ADR-006: any member of the buyer company may read the private catalog; removal from the company ends access.
  jdbc.update("UPDATE company_memberships SET role='MEMBER' WHERE company_id=?",b.company().toString());
  assertEquals(p.id(),catalog.buyerGet(b.company(),s.company(),p.id(),b.auth()).id());
  jdbc.update("DELETE FROM company_memberships WHERE company_id=?",b.company().toString());
  error("COMPANY_NOT_FOUND",()->catalog.buyerGet(b.company(),s.company(),p.id(),b.auth()));
  var draft=catalog.transition(s.company(),p.id(),p.version(),ProductStatus.DRAFT,s.auth());
  var archived=catalog.transition(s.company(),p.id(),draft.version(),ProductStatus.ARCHIVED,s.auth());
  error("PRODUCT_STATE_CONFLICT",()->catalog.transition(s.company(),p.id(),archived.version(),ProductStatus.PUBLISHED,s.auth()));
 }
 @Test void publicExplicitVisibilityNeverIncludesPriceOrSku(){
  var x=setup();var s=x.seller();var p=x.product();error("PRODUCT_NOT_FOUND",()->publicCatalog.get(p.id()));
  var visible=catalog.visibility(s.company(),p.id(),new CatalogDtos.Visibility(p.version(),true),s.auth());sellers.update(s.company(),false,s.auth());
  assertEquals(1,publicCatalog.list(s.company(),null,null,null,0,20).totalElements());
  var node=json.valueToTree(publicCatalog.get(p.id()));assertEquals(10,node.size()); // ADR-011 added brand and unit; ADR-010 added retailPrice; ADR-007 added categoryId and imageIds; still no price or SKU
  for(String key:List.of("sku","price","currency","taxIncluded","version","buyerCompanyIds"))assertFalse(node.has(key));
  catalog.visibility(s.company(),p.id(),new CatalogDtos.Visibility(visible.version(),false),s.auth());
  error("PRODUCT_NOT_FOUND",()->publicCatalog.get(p.id()));assertEquals(0,publicCatalog.list(s.company(),null,null,null,0,20).totalElements());
 }
 @Test void priceAndGrantValidationVersionAndAuditRollback(){
  var s=actor();var b=actor();approve(b,s);
  for(String amount:List.of("-1","1e2","1.001","100000000000000000","NaN"))
   error("VALIDATION_FAILED",()->catalog.create(s.company(),new CatalogDtos.Create(UUID.randomUUID().toString(),"Name","",price(amount,"TRY"),null,null,null),s.auth()));
  error("VALIDATION_FAILED",()->catalog.create(s.company(),new CatalogDtos.Create("JPY","Name","",price("1","JPY"),null,null,null),s.auth()));
  var max=catalog.create(s.company(),new CatalogDtos.Create("max","Name","",price("99999999999999999.99","EUR"),null,null,null),s.auth());assertEquals("99999999999999999.99",max.price().amount());
  var p=create(s,"  lower  ");assertEquals("LOWER",p.sku());
  error("INVALID_BUYER_SELECTION",()->catalog.replaceBuyers(s.company(),p.id(),new CatalogDtos.BuyersInput(0L,List.of(b.company(),b.company())),s.auth()));
  error("INVALID_BUYER_SELECTION",()->catalog.replaceBuyers(s.company(),p.id(),new CatalogDtos.BuyersInput(0L,List.of(b.company(),UUID.randomUUID())),s.auth()));
  assertEquals(0,catalog.buyers(s.company(),p.id(),s.auth()).productVersion());
  assertTrue(catalog.buyers(s.company(),p.id(),s.auth()).buyerCompanyIds().isEmpty());
  assertEquals(1,catalog.replaceBuyers(s.company(),p.id(),new CatalogDtos.BuyersInput(0L,List.of(b.company())),s.auth()).productVersion());
  error("PRODUCT_VERSION_CONFLICT",()->catalog.transition(s.company(),p.id(),0,ProductStatus.PUBLISHED,s.auth()));
  doThrow(new IllegalStateException("synthetic audit failure")).when(events).saveAndFlush(any(ProductEvent.class));
  try{
   assertThrows(IllegalStateException.class,()->catalog.replaceBuyers(s.company(),p.id(),new CatalogDtos.BuyersInput(1L,List.of()),s.auth()));
   assertThrows(IllegalStateException.class,()->catalog.transition(s.company(),p.id(),1,ProductStatus.PUBLISHED,s.auth()));
   assertThrows(IllegalStateException.class,()->create(s,"ROLLBACK"));
   assertThrows(IllegalStateException.class,()->catalog.edit(s.company(),p.id(),new CatalogDtos.Edit(1L,"LOWER","Changed","",price("0","USD"),null,null,null),s.auth()));
  }finally{reset(events);}
  assertEquals(List.of(b.company()),catalog.buyers(s.company(),p.id(),s.auth()).buyerCompanyIds());assertEquals(1,catalog.get(s.company(),p.id(),s.auth()).version());
  assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product_events WHERE product_id=?",Long.class,p.id().toString()));
  assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_products WHERE seller_company_id=? AND sku='ROLLBACK'",Long.class,s.company().toString()));
 }
 @Test void committedChangesDefeatReadPausedAfterFirstOwnerCheck() throws Exception{
  for(String action:List.of("REVOKE","GRANT","UNPUBLISH","MEMBERSHIP")){
   var x=setup();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var once=new AtomicBoolean();
   doAnswer(invocation->{
    Object result=invocation.callRealMethod();
    if(Thread.currentThread().getName().equals("paused-catalog-read")&&once.compareAndSet(false,true)){
     entered.countDown();if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timed out");
    }return result;
   }).when(access).requireMember(eq(x.buyer().company()),any(Authentication.class));
   var pool=Executors.newSingleThreadExecutor(r->new Thread(r,"paused-catalog-read"));
   try{
    var pending=pool.submit(()->catalog.buyerGet(x.buyer().company(),x.seller().company(),x.product().id(),x.buyer().auth()));
    assertTrue(entered.await(5,TimeUnit.SECONDS));
    switch(action){
     case "REVOKE"->trade.decide(x.seller().company(),x.relation(),1,TradeStatus.REVOKED,x.seller().auth());
     case "GRANT"->catalog.replaceBuyers(x.seller().company(),x.product().id(),new CatalogDtos.BuyersInput(x.product().version(),List.of()),x.seller().auth());
     case "UNPUBLISH"->catalog.transition(x.seller().company(),x.product().id(),x.product().version(),ProductStatus.DRAFT,x.seller().auth());
     case "MEMBERSHIP"->jdbc.update("DELETE FROM company_memberships WHERE company_id=?",x.buyer().company().toString());
    }
    resume.countDown();var failure=assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));
    assertInstanceOf(ApiException.class,failure.getCause());assertEquals(action.equals("MEMBERSHIP")?"COMPANY_NOT_FOUND":"PRODUCT_NOT_FOUND",((ApiException)failure.getCause()).code());
   }finally{resume.countDown();pool.shutdownNow();reset(access);}
  }
 }
 /** ARCH-M43-01: M2 commercial writes re-read the actor's role after the seller lock under READ_COMMITTED. */
 @Test void commercialWritesRecheckRoleAfterLock() throws Exception{
  for(String action:List.of("APPLY","DECIDE","PROFILE","ROTATE","REVOKE_CODE")){
   var s=actor();var b=actor();sellers.update(s.company(),true,s.auth());
   var pending=action.equals("DECIDE")?trade.apply(b.company(),new TradeDtos.Apply(s.company(),null),b.auth()):null;
   var who=action.equals("APPLY")?b:s;
   var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var once=new AtomicBoolean();
   doAnswer(invocation->{
    Object result=invocation.callRealMethod();
    if(Thread.currentThread().getName().equals("paused-commercial-write")&&once.compareAndSet(false,true)){
     entered.countDown();if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timed out");
    }return result;
   }).when(access).requireAdmin(eq(who.company()),any(Authentication.class));
   var pool=Executors.newSingleThreadExecutor(r->new Thread(r,"paused-commercial-write"));
   try{
    Callable<Object> write=switch(action){
     case "APPLY"->()->trade.apply(b.company(),new TradeDtos.Apply(s.company(),null),b.auth());
     case "DECIDE"->()->trade.decide(s.company(),pending.id(),0,TradeStatus.APPROVED,s.auth());
     case "PROFILE"->()->sellers.update(s.company(),false,s.auth());
     case "ROTATE"->()->invitations.rotate(s.company(),s.auth());
     default->()->{invitations.revoke(s.company(),s.auth());return null;};
    };
    var future=pool.submit(write);
    assertTrue(entered.await(5,TimeUnit.SECONDS),action);
    // Committed while the write waits between its first role check and the lock.
    jdbc.update("UPDATE company_memberships SET role='MEMBER' WHERE company_id=? AND user_id=?",who.company().toString(),who.user().toString());
    resume.countDown();
    var failure=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS),action+" must not succeed with the old role");
    assertInstanceOf(ApiException.class,failure.getCause(),action);
    assertEquals("ADMIN_REQUIRED",((ApiException)failure.getCause()).code(),action);
   }finally{resume.countDown();pool.shutdownNow();reset(access);}
  }
 }
 @Test void independentReadCommittedEntryAndCompetingWrites() throws Exception{
  var x=setup();var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  doAnswer(invocation->{assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));return invocation.callRealMethod();}).when(access).requireOwner(eq(x.buyer().company()),any(Authentication.class));
  try{tx.executeWithoutResult(status->catalog.buyerGet(x.buyer().company(),x.seller().company(),x.product().id(),x.buyer().auth()));}finally{reset(access);}
  var results=race(()->catalog.transition(x.seller().company(),x.product().id(),x.product().version(),ProductStatus.DRAFT,x.seller().auth()),()->catalog.transition(x.seller().company(),x.product().id(),x.product().version(),ProductStatus.ARCHIVED,x.seller().auth()));
  assertEquals(1,results.stream().filter(CatalogDtos.Owner.class::isInstance).count());
  assertEquals(1,results.stream().filter(v->v instanceof ApiException e&&e.code().equals("PRODUCT_VERSION_CONFLICT")).count());
  var s=actor();results=race(()->create(s,"same"),()->create(s," SAME "));
  assertEquals(1,results.stream().filter(CatalogDtos.Owner.class::isInstance).count());
  assertEquals(1,results.stream().filter(v->v instanceof ApiException e&&e.code().equals("SKU_CONFLICT")).count());
 }
 @Test void productCeilingIsSerializedAndGlobalQuotasCrossCompany() throws Exception{
  var s=actor();List<Object[]> rows=new ArrayList<>();
  for(int i=0;i<999;i++)rows.add(new Object[]{UUID.randomUUID().toString(),s.company().toString(),"SEED"+i});
  jdbc.batchUpdate("INSERT INTO catalog_products(id,seller_company_id,sku,name,description,status,public_visible,b2b_amount,currency,tax_included,version,created_at,updated_at) VALUES(?,?,?,'Synthetic seed','','DRAFT',false,0,'TRY',false,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",rows);
  var results=race(()->create(s,"LAST_A"),()->create(s,"LAST_B"));
  assertEquals(1,results.stream().filter(CatalogDtos.Owner.class::isInstance).count());assertEquals(1,results.stream().filter(v->v instanceof ApiException e&&e.code().equals("CATALOG_LIMIT_REACHED")).count());
  assertEquals(1000L,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_products WHERE seller_company_id=?",Long.class,s.company().toString()));
  var quota=actor();var second=companies.create("Second quota company",quota.auth()).id();
  for(int i=0;i<30;i++)error("VALIDATION_FAILED",()->catalog.create(quota.company(),new CatalogDtos.Create("!","bad","",price("1","TRY"),null,null,null),quota.auth()));
  error("RATE_LIMITED",()->catalog.create(second,input("quota"),quota.auth()));
 }
 @Test void httpSecurityValidationPublicDtoAndPayloadLimits() throws Exception{
  var x=setup();var client=client();login(client,x.seller());String csrf=csrf(client);
  String base="/api/v1/companies/"+x.seller().company()+"/products",item=base+"/"+x.product().id();
  assertEquals(403,request(client,"PUT",item+"/public-visibility","{\"expectedVersion\":2,\"publicVisible\":true}",null,"application/json",false).statusCode());
  assertEquals(400,request(client,"POST",base,"{\"sku\":\"hack\",\"name\":\"N\",\"price\":{\"amount\":\"1\",\"currency\":\"TRY\",\"taxIncluded\":false},\"publicVisible\":true}",csrf,"application/json",false).statusCode());
  for(String value:List.of("1","null","\"1e2\"","\"-1\"","\"1.001\"")){
   String body="{\"sku\":\"bad\",\"name\":\"N\",\"price\":{\"amount\":"+value+",\"currency\":\"TRY\",\"taxIncluded\":false}}";
   assertEquals(400,request(client,"POST",base,body,csrf,"application/json",false).statusCode());
  }
  assertEquals(413,request(client,"PUT",item+"/buyers","{\"padding\":\""+"a".repeat(17000)+"\"}",csrf,"application/vnd.ticarvo+json",true).statusCode());
  assertEquals(200,request(client,"PUT",item+"/public-visibility","{\"expectedVersion\":2,\"publicVisible\":true}",csrf,"application/json",false).statusCode());
  for(HttpClient viewer:List.of(client,client())){
   var response=request(viewer,"GET","/api/v1/catalog/products/"+x.product().id(),null,null,null,false);
   assertEquals(200,response.statusCode(),response.body());assertEquals(10,json.readTree(response.body()).size());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
  }
  assertEquals(401,request(client(),"GET","/api/v1/companies/"+x.buyer().company()+"/catalog/sellers/"+x.seller().company()+"/products",null,null,null,false).statusCode());
  assertEquals(400,request(client,"GET",base+"?size=101",null,null,null,false).statusCode());
 }
 List<Object> race(Callable<?> a,Callable<?> b)throws Exception{
  var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
  try{List<Future<Object>> futures=new ArrayList<>();for(var action:List.of(a,b))futures.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{return action.call();}catch(ApiException e){return e;}}));
   return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
  }finally{pool.shutdownNow();}
 }
 HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
 String csrf(HttpClient c)throws Exception{return json.readTree(request(c,"GET","/api/v1/auth/csrf",null,null,null,false).body()).get("token").asString();}
 void login(HttpClient c,Actor actor)throws Exception{assertEquals(204,request(c,"POST","/api/v1/auth/login","email="+URLEncoder.encode(actor.email(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8),csrf(c),"application/x-www-form-urlencoded",false).statusCode());}
 HttpResponse<String> request(HttpClient client,String method,String path,String body,String csrf,String type,boolean chunked)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);if(type!=null)b.header("Content-Type",type);
  var publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body);if(chunked)publisher=HttpRequest.BodyPublishers.fromPublisher(publisher);
  return client.send(b.method(method,publisher).build(),HttpResponse.BodyHandlers.ofString());
 }

 @Test void readHoldingSellerLockCompletesBeforeWaitingRevoke() throws Exception {
  var x=setup();var locked=new CountDownLatch(1);var resume=new CountDownLatch(1);
  new TransactionTemplate(transactions).executeWithoutResult(status -> doAnswer(invocation->{
   Object result=invocation.callRealMethod();
   if(Thread.currentThread().getName().equals("locked-reader")){locked.countDown();assertTrue(resume.await(10,TimeUnit.SECONDS));}
   return result;
  }).when(access).lockSellerForRead(x.seller().company())); // A-02: reads hold the shared lock; the revoke below still waits for it
  var reader=Executors.newSingleThreadExecutor(r->new Thread(r,"locked-reader"));
  var writer=Executors.newSingleThreadExecutor();
  try{
   var reading=reader.submit(()->catalog.buyerGet(x.buyer().company(),x.seller().company(),x.product().id(),x.buyer().auth()));
   assertTrue(locked.await(5,TimeUnit.SECONDS));
   var started=new CountDownLatch(1);
   var revoking=writer.submit(()->{started.countDown();return trade.decide(x.seller().company(),x.relation(),1,TradeStatus.REVOKED,x.seller().auth());});
   assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->revoking.get(250,TimeUnit.MILLISECONDS));
   resume.countDown();assertEquals(x.product().id(),reading.get(10,TimeUnit.SECONDS).id());assertEquals(TradeStatus.REVOKED,revoking.get(10,TimeUnit.SECONDS).status());
   error("PRODUCT_NOT_FOUND",()->catalog.buyerGet(x.buyer().company(),x.seller().company(),x.product().id(),x.buyer().auth()));
  }finally{resume.countDown();reader.shutdownNow();writer.shutdownNow();reset(access);}
 }
}
