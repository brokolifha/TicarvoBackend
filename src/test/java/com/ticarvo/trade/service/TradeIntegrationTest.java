package com.ticarvo.trade.service;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.service.CompanyAccessService;
import com.ticarvo.company.service.CompanyService;
import com.ticarvo.company.service.SellerDirectoryService;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.service.IdentityService;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.entity.TradeEvent;
import com.ticarvo.trade.model.TradeStatus;
import com.ticarvo.trade.repository.TradeEventRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties="ticarvo.cors.allowed-origins=http://localhost:5173")
@ActiveProfiles("local")
@Import(TradeIntegrationTest.TestClockConfiguration.class)
class TradeIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required; real MySQL tests never skip.");
        return value;
    }
    @Autowired IdentityService identity;
    @Autowired CompanyService companies;
    @Autowired CompanyAccessService companyAccess;
    @Autowired SellerDirectoryService sellers;
    @Autowired TradeService trade;
    @Autowired InvitationService invitations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @MockitoSpyBean TradeEventRepository events;
    @Value("${local.server.port}") int port;
    static final String PASSWORD="Synthetic-M2-Password!95";
    record Actor(UUID userId,String email,Authentication auth,UUID company) {}
    Actor actor() {
        String email="m2-"+UUID.randomUUID()+"@example.invalid";
        var user=identity.register(new RegisterRequest(email,PASSWORD,"Synthetic owner"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",user.id().toString());
        var auth=new UsernamePasswordAuthenticationToken(user.email(),"",List.of());
        return new Actor(user.id(),user.email(),auth,companies.create("Company "+UUID.randomUUID(),auth).id());
    }
    TradeDtos.Relationship apply(Actor buyer,Actor seller) {
        return trade.apply(buyer.company(),new TradeDtos.Apply(seller.company(),null),buyer.auth());
    }
    static ApiException error(String code,Runnable action) {
        var exception=assertThrows(ApiException.class,action::run); assertEquals(code,exception.code()); return exception;
    }
    long eventCount(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trade_relationship_events WHERE relationship_id=?",Long.class,id.toString());
    }
    @Test void directoryAndDirectionalLifecycleWithIsolation() {
        Actor a=actor(), b=actor(), c=actor();
        error("SELLER_NOT_FOUND",()->sellers.get(b.company()));
        assertFalse(sellers.profile(b.company(),b.auth()).listed());
        error("COMPANY_NOT_FOUND",()->sellers.update(b.company(),true,c.auth()));
        sellers.update(b.company(),true,b.auth());
        assertEquals(b.company(),sellers.get(b.company()).id());
        var pending=apply(a,b);
        assertEquals(TradeStatus.PENDING,pending.status()); assertFalse(trade.hasApprovedRelationship(a.company(),b.company()));
        error("RELATIONSHIP_EXISTS",()->apply(a,b));
        error("SELLER_OWNER_REQUIRED",()->trade.decide(a.company(),pending.id(),0,TradeStatus.APPROVED,a.auth()));
        error("RELATIONSHIP_NOT_FOUND",()->trade.decide(c.company(),pending.id(),999,TradeStatus.APPROVED,c.auth()));
        error("COMPANY_NOT_FOUND",()->trade.get(b.company(),pending.id(),c.auth()));
        assertEquals(1,trade.list(a.company(),TradeDtos.Direction.OUTGOING,null,0,20,a.auth()).totalElements());
        assertEquals(0,trade.list(c.company(),TradeDtos.Direction.INCOMING,null,0,20,c.auth()).totalElements());
        var approved=trade.decide(b.company(),pending.id(),pending.version(),TradeStatus.APPROVED,b.auth());
        assertEquals(1,approved.version()); assertTrue(trade.hasApprovedRelationship(a.company(),b.company()));
        assertFalse(trade.hasApprovedRelationship(b.company(),a.company()));
        sellers.update(b.company(),false,b.auth());
        assertTrue(trade.hasApprovedRelationship(a.company(),b.company()));
        error("SELLER_NOT_FOUND",()->sellers.get(b.company()));
        var revoked=trade.decide(b.company(),pending.id(),approved.version(),TradeStatus.REVOKED,b.auth());
        assertEquals(2,revoked.version()); assertFalse(trade.hasApprovedRelationship(a.company(),b.company()));
        error("RELATIONSHIP_VERSION_CONFLICT",()->trade.decide(b.company(),pending.id(),0,TradeStatus.APPROVED,b.auth()));
        error("RELATIONSHIP_STATE_CONFLICT",()->trade.decide(b.company(),pending.id(),2,TradeStatus.APPROVED,b.auth()));
        sellers.update(b.company(),true,b.auth());
        error("REAPPLY_TOO_SOON",()->apply(a,b)); // A-03: a closed pair may ask again, but not at once
        assertEquals(3,eventCount(pending.id()));
        assertEquals(List.of(0L,1L,2L),jdbc.queryForList("SELECT relationship_version FROM trade_relationship_events WHERE relationship_id=? ORDER BY relationship_version",Long.class,pending.id().toString()));
        assertEquals(a.userId().toString(),jdbc.queryForObject("SELECT actor_user_id FROM trade_relationship_events WHERE relationship_id=? AND action='APPLIED'",String.class,pending.id().toString()));
        // A-03: after 30 days the same pair asks again on the same row; the seller decides anew.
        Instant decided=clock.instant();
        try {
            clock.now=decided.plus(java.time.Duration.ofDays(31));
            var again=apply(a,b);
            assertEquals(pending.id(),again.id()); assertEquals(TradeStatus.PENDING,again.status()); assertEquals(3,again.version());
            assertFalse(trade.hasApprovedRelationship(a.company(),b.company()),"asking again grants nothing");
            assertEquals("REAPPLIED",jdbc.queryForObject("SELECT action FROM trade_relationship_events WHERE relationship_id=? AND relationship_version=3",String.class,pending.id().toString()));
            error("RELATIONSHIP_EXISTS",()->apply(a,b));
        } finally { clock.now=decided; }
        error("SELF_RELATIONSHIP_NOT_ALLOWED",()->apply(b,b));
        jdbc.update("UPDATE company_memberships SET role='MEMBER' WHERE company_id=? AND user_id=?",b.company().toString(),b.userId().toString());
        // ADR-006: a MEMBER may view the seller profile but not change it.
        assertEquals(b.company(),sellers.profile(b.company(),b.auth()).companyId());
        assertEquals(403,error("ADMIN_REQUIRED",()->sellers.update(b.company(),false,b.auth())).status().value());
        jdbc.update("DELETE FROM company_memberships WHERE company_id=? AND user_id=?",b.company().toString(),b.userId().toString());
        error("COMPANY_NOT_FOUND",()->trade.get(b.company(),pending.id(),b.auth()));
    }
    @Test void privateInvitationHasHashExpiryRotationAndNoAutomaticApproval() {
        Actor a=actor(),b=actor(),c=actor();
        error("SELLER_NOT_FOUND",()->apply(a,b));
        var first=invitations.rotate(b.company(),b.auth());
        assertTrue(first.code().matches("[A-Za-z0-9_-]{32}"));
        String stored=jdbc.queryForObject("SELECT code_hash FROM seller_invitations WHERE seller_company_id=?",String.class,b.company().toString());
        assertEquals(InvitationService.hash(first.code()),stored); assertNotEquals(first.code(),stored);
        assertEquals(b.company(),invitations.resolve(first.code(),a.auth()).id());
        var pending=trade.apply(a.company(),new TradeDtos.Apply(null,first.code()),a.auth());
        assertEquals(TradeStatus.PENDING,pending.status());
        var second=invitations.rotate(b.company(),b.auth());
        error("INVITATION_NOT_FOUND",()->invitations.resolve(first.code(),a.auth()));
        error("INVITATION_NOT_FOUND",()->trade.apply(c.company(),new TradeDtos.Apply(null,first.code()),c.auth()));
        assertEquals(b.company(),invitations.resolve(second.code(),c.auth()).id());
        var another=trade.apply(c.company(),new TradeDtos.Apply(null,second.code()),c.auth());
        assertEquals(TradeStatus.PENDING,another.status());
        invitations.revoke(b.company(),b.auth());
        error("INVITATION_NOT_FOUND",()->invitations.resolve(second.code(),a.auth()));
        assertEquals(TradeStatus.PENDING,trade.get(a.company(),pending.id(),a.auth()).status());
        var expiring=invitations.rotate(b.company(),b.auth());
        Instant original=clock.instant();
        try {
            clock.now=expiring.expiresAt().minusNanos(1000);
            assertEquals(b.company(),invitations.resolve(expiring.code(),c.auth()).id());
            clock.now=expiring.expiresAt();
            error("INVITATION_NOT_FOUND",()->invitations.resolve(expiring.code(),c.auth()));
        } finally { clock.now=original; }
    }
    @Test void duplicateApplicationsRaceHasOneRowAndOneAudit() throws Exception {
        Actor a=actor(),b=actor(); sellers.update(b.company(),true,b.auth());
        var results=race(()->apply(a,b),()->apply(a,b));
        assertEquals(1,results.stream().filter(TradeDtos.Relationship.class::isInstance).count());
        assertEquals(1,results.stream().filter(x->x instanceof ApiException e && e.code().equals("RELATIONSHIP_EXISTS")).count());
        var relation=(TradeDtos.Relationship)results.stream().filter(TradeDtos.Relationship.class::isInstance).findFirst().orElseThrow();
        assertEquals(1,eventCount(relation.id()));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM trade_relationships WHERE buyer_company_id=? AND seller_company_id=?",Long.class,a.company().toString(),b.company().toString()));
    }
    @Test void simultaneousApproveRejectHasSingleWinnerAndAudit() throws Exception {
        Actor a=actor(),b=actor(); sellers.update(b.company(),true,b.auth());
        var relationship=apply(a,b);
        var results=race(
            ()->trade.decide(b.company(),relationship.id(),0,TradeStatus.APPROVED,b.auth()),
            ()->trade.decide(b.company(),relationship.id(),0,TradeStatus.REJECTED,b.auth()));
        assertEquals(1,results.stream().filter(TradeDtos.Relationship.class::isInstance).count());
        assertEquals(1,results.stream().filter(x->x instanceof ApiException e && e.code().equals("RELATIONSHIP_VERSION_CONFLICT")).count());
        assertEquals(2,eventCount(relationship.id()));
    }
    @Test void revokeCommitDefeatsApplyWaitingOnSellerLock() throws Exception {
        Actor a=actor(),b=actor(); var code=invitations.rotate(b.company(),b.auth()).code();
        var executor=Executors.newSingleThreadExecutor();
        final Future<?>[] pending=new Future<?>[1];
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                companyAccess.lockSeller(b.company());
                invitations.revoke(b.company(),b.auth());
                var started=new CountDownLatch(1);
                pending[0]=executor.submit(()->{started.countDown();return trade.apply(a.company(),new TradeDtos.Apply(null,code),a.auth());});
                try { assertTrue(started.await(5,TimeUnit.SECONDS)); assertThrows(TimeoutException.class,()->pending[0].get(250,TimeUnit.MILLISECONDS)); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            });
            var failure=assertThrows(ExecutionException.class,()->pending[0].get(10,TimeUnit.SECONDS));
            assertInstanceOf(ApiException.class,failure.getCause());
            assertEquals("INVITATION_NOT_FOUND",((ApiException)failure.getCause()).code());
        } finally { executor.shutdownNow(); }
    }
    @Test void auditFailureRollsBackRelationshipAndDecision() {
        Actor a=actor(),b=actor(); sellers.update(b.company(),true,b.auth());
        doThrow(new IllegalStateException("synthetic audit failure")).when(events).saveAndFlush(any(TradeEvent.class));
        try { assertThrows(IllegalStateException.class,()->apply(a,b)); }
        finally { reset(events); }
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM trade_relationships WHERE buyer_company_id=? AND seller_company_id=?",Long.class,a.company().toString(),b.company().toString()));
        var relationship=apply(a,b);
        doThrow(new IllegalStateException("synthetic audit failure")).when(events).saveAndFlush(any(TradeEvent.class));
        try { assertThrows(IllegalStateException.class,()->trade.decide(b.company(),relationship.id(),0,TradeStatus.APPROVED,b.auth())); }
        finally { reset(events); }
        assertEquals(TradeStatus.PENDING,trade.get(a.company(),relationship.id(),a.auth()).status());
        assertEquals(0,trade.get(a.company(),relationship.id(),a.auth()).version()); assertEquals(1,eventCount(relationship.id()));
    }
    @Test void quotasAreGlobalAcrossCompaniesAndOtherPrincipalIsIndependent() {
        Actor a=actor(),b=actor(); UUID second=companies.create("Second buyer",a.auth()).id();
        for(int i=0;i<30;i++) error("SELLER_NOT_FOUND",()->trade.apply(a.company(),new TradeDtos.Apply(UUID.randomUUID(),null),a.auth()));
        error("RATE_LIMITED",()->trade.apply(second,new TradeDtos.Apply(b.company(),null),a.auth()));
        error("SELLER_NOT_FOUND",()->trade.apply(b.company(),new TradeDtos.Apply(a.company(),null),b.auth()));
        for(int i=0;i<8;i++) companies.create("Bounded "+i,a.auth());
        error("RATE_LIMITED",()->companies.create("Excess",a.auth()));
        for(int i=0;i<60;i++) error("INVITATION_NOT_FOUND",()->invitations.resolve("a".repeat(32),b.auth()));
        error("RATE_LIMITED",()->invitations.resolve("a".repeat(32),b.auth()));
    }
    @Test void realHttpCookieCsrfMethodsPayloadAndOwnerIsolation() throws Exception {
        Actor a=actor(),b=actor(); var ca=client(); var cb=client();
        login(ca,a); login(cb,b);
        String csrfA=csrf(ca),csrfB=csrf(cb),base="/api/v1/companies/"+b.company();
        assertEquals(403,request(cb,"PUT",base+"/seller-profile","{\"listed\":true}",null,"application/json",false).statusCode());
        assertEquals(400,request(cb,"PUT",base+"/seller-profile","{\"listed\":true,\"role\":\"OWNER\"}",csrfB,"application/json",false).statusCode());
        assertEquals(200,request(cb,"PUT",base+"/seller-profile","{\"listed\":true}",csrfB,"application/json",false).statusCode());
        var publicSeller=request(client(),"GET","/api/v1/sellers/"+b.company(),null,null,null,false);
        assertEquals(200,publicSeller.statusCode()); assertEquals(8,mapper.readTree(publicSeller.body()).size()); // id, name and, since ADR-011, shipping terms and the public profile (no billing details)
        assertTrue(publicSeller.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(404,request(ca,"PUT",base+"/seller-profile","{\"listed\":false}",csrfA,"application/json",false).statusCode());
        String tooLarge="{\"listed\":true,\"padding\":\""+"a".repeat(17000)+"\"}";
        assertEquals(413,request(cb,"PUT",base+"/seller-profile",tooLarge,csrfB,"application/vnd.ticarvo+json",true).statusCode());
        assertEquals(413,request(cb,"DELETE",base+"/trade-invitation",tooLarge,csrfB,"application/vnd.ticarvo+json",true).statusCode());
        String applyPath="/api/v1/companies/"+a.company()+"/trade-relationships";
        assertEquals(403,request(ca,"POST",applyPath,"{\"sellerCompanyId\":\""+b.company()+"\"}",null,"application/json",false).statusCode());
        var applied=request(ca,"POST",applyPath,"{\"sellerCompanyId\":\""+b.company()+"\"}",csrfA,"application/json",false);
        assertEquals(201,applied.statusCode(),applied.body());
        String id=mapper.readTree(applied.body()).get("id").asText();
        assertEquals(403,request(ca,"POST",applyPath+"/"+id+"/approve","{\"expectedVersion\":0}",csrfA,"application/json",false).statusCode());
        assertEquals(200,request(cb,"POST",base+"/trade-relationships/"+id+"/approve","{\"expectedVersion\":0}",csrfB,"application/json",false).statusCode());
        var preflight=HttpRequest.newBuilder(URI.create(url()+base+"/seller-profile")).method("OPTIONS",HttpRequest.BodyPublishers.noBody())
            .header("Origin","http://localhost:5173").header("Access-Control-Request-Method","PUT").build();
        assertEquals(200,cb.send(preflight,HttpResponse.BodyHandlers.ofString()).statusCode());
        jdbc.update("DELETE FROM company_memberships WHERE company_id=? AND user_id=?",b.company().toString(),b.userId().toString());
        assertEquals(404,request(cb,"GET",base+"/trade-relationships/"+id,null,null,null,false).statusCode());
        assertEquals(204,request(ca,"POST","/api/v1/auth/logout",null,csrfA,null,false).statusCode());
        assertEquals(401,request(ca,"GET","/api/v1/auth/me",null,null,null,false).statusCode());
    }
    List<Object> race(Callable<?> a,Callable<?> b) throws Exception {
        var barrier=new CyclicBarrier(2); var executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures=new ArrayList<>();
            for(var action:List.of(a,b)) futures.add(executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{return action.call();}catch(ApiException e){return e;}}));
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    String url(){return "http://127.0.0.1:"+port;}
    HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    String csrf(HttpClient client) throws Exception {return mapper.readTree(request(client,"GET","/api/v1/auth/csrf",null,null,null,false).body()).get("token").asText();}
    void login(HttpClient client,Actor actor) throws Exception {
        String token=csrf(client);
        var response=request(client,"POST","/api/v1/auth/login","email="+URLEncoder.encode(actor.email(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8),token,"application/x-www-form-urlencoded",false);
        assertEquals(204,response.statusCode(),response.body());
    }
    HttpResponse<String> request(HttpClient client,String method,String path,String body,String csrf,String type,boolean chunked) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url()+path)).timeout(Duration.ofSeconds(10));
        if(csrf!=null)builder.header("X-CSRF-TOKEN",csrf); if(type!=null)builder.header("Content-Type",type);
        var publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body);
        if(chunked) publisher=HttpRequest.BodyPublishers.fromPublisher(publisher);
        return client.send(builder.method(method,publisher).build(),HttpResponse.BodyHandlers.ofString());
    }
    @TestConfiguration static class TestClockConfiguration {
        @Bean @Primary MutableClock testClock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        volatile Instant now=Instant.now();
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
}
