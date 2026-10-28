package com.ticarvo.admin.service;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.service.IdentityService;
import com.ticarvo.identity.service.MfaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-008 platform admin, complaints, suspensions and appeals over real HTTP and MySQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AdminIntegrationTest {
    static final Path OUTBOX=tempDirectory("ticarvo-m4a-outbox");
    static final String PASSWORD="Synthetic-M4A-Password!95";
    static final String STATEMENT="Synthetic statement of reasons for this decision.";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",OUTBOX::toString);
        registry.add("ticarvo.storage.image-dir",()->tempDirectory("ticarvo-m4a-images").toString());
        // ADR-011: this class runs with the production rule, new companies need admin verification to sell.
        registry.add("ticarvo.sellers.verification-required",()->"true");
    }
    @Autowired IdentityService identity;
    @Autowired AdminAccess admins;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    record User(UUID id,String email,HttpClient http) {}
    record Admin(User session,String email,Deque<String> recoveryCodes) {}

    @Test void adminPermissionNeedsTheGrantAndAnMfaSessionAndIsRevokedAtOnce() throws Exception {
        assertEquals("401 AUTHENTICATION_REQUIRED",outcome(request(null,"GET","/api/v1/admin/users",null,null)));
        var plain=user();
        assertEquals("403 PLATFORM_ADMIN_REQUIRED",outcome(send(plain,"GET","/api/v1/admin/users",null)));
        assertThrows(IllegalStateException.class,()->admins.grant(plain.email()),"an account without MFA cannot become admin");

        var admin=admin();
        // The session that enabled MFA did not log in with a code.
        assertEquals("403 MFA_REQUIRED",outcome(send(admin.session(),"GET","/api/v1/admin/users",null)));
        var session=login(admin);
        assertEquals(200,send(session,"GET","/api/v1/admin/users?q="+URLEncoder.encode(plain.email(),StandardCharsets.UTF_8),null).statusCode());
        var found=json.readTree(send(session,"GET","/api/v1/admin/users?q="+URLEncoder.encode(plain.email(),StandardCharsets.UTF_8),null).body());
        assertEquals(1,found.get("totalElements").asLong());
        assertFalse(found.get("content").get(0).toString().toLowerCase(Locale.ROOT).contains("password"),"no credential fields");
        assertEquals(403,request(session,"POST","/api/v1/admin/users/"+plain.id()+"/suspension",
            "{\"reason\":\"OTHER\",\"statement\":\""+STATEMENT+"\"}","application/json",false).statusCode(),"admin changes need CSRF");

        // No company bypass: a platform admin is not an owner of someone else's company.
        UUID company=company(plain);
        assertEquals("404 COMPANY_NOT_FOUND",outcome(send(session,"GET","/api/v1/companies/"+company+"/members",null)));
        var detail=json.readTree(send(session,"GET","/api/v1/admin/companies/"+company,null).body());
        assertEquals(plain.email(),detail.get("members").get(0).get("email").asString());

        admins.revoke(admin.email());
        assertEquals("403 PLATFORM_ADMIN_REQUIRED",outcome(send(session,"GET","/api/v1/admin/users",null)),"revocation applies to open sessions");
        assertEquals(List.of("ADMIN_GRANTED","ADMIN_REVOKED"),jdbc.queryForList(
            "SELECT action FROM admin_audit_log WHERE target_id=? ORDER BY created_at",String.class,admin.session().id().toString()));
    }

    @Test void complaintsStayWithTheReporterAndFollowTheStateMachine() throws Exception {
        var seller=user(); UUID s=company(seller); var other=user(); UUID o=company(other);
        String product=json.readTree(send(seller,"POST","/api/v1/companies/"+s+"/products",
            "{\"sku\":\"CMP-1\",\"name\":\"Complained\",\"price\":{\"amount\":\"1\",\"currency\":\"TRY\",\"taxIncluded\":false}}").body()).get("id").asString();
        var reporter=user(); var stranger=user();
        assertEquals(400,send(reporter,"POST","/api/v1/complaints",complaint(s,null,"too short")).statusCode());
        assertEquals("404 PRODUCT_NOT_FOUND",outcome(send(reporter,"POST","/api/v1/complaints",complaint(o,product,"Product of another company."+" Long enough."))));
        var created=send(reporter,"POST","/api/v1/complaints",complaint(s,product,"This listing looks counterfeit to me."));
        assertEquals(201,created.statusCode(),created.body());
        String id=json.readTree(created.body()).get("id").asString();
        assertEquals("OPEN",json.readTree(created.body()).get("status").asString());
        assertEquals("409 COMPLAINT_DUPLICATE",outcome(send(reporter,"POST","/api/v1/complaints",complaint(s,product,"Same target again, still open."))));
        assertEquals("404 COMPLAINT_NOT_FOUND",outcome(send(stranger,"GET","/api/v1/complaints/"+id,null)),"another user's complaint is invisible");
        assertEquals("404 COMPLAINT_NOT_FOUND",outcome(send(seller,"GET","/api/v1/complaints/"+id,null)),"the reported side never sees it");
        assertEquals(1,json.readTree(send(reporter,"GET","/api/v1/complaints",null).body()).get("totalElements").asLong());
        assertEquals("403 PLATFORM_ADMIN_REQUIRED",outcome(send(reporter,"GET","/api/v1/admin/complaints/"+id,null)));

        var admin=login(admin());
        String c="/api/v1/admin/complaints/"+id;
        assertEquals("IN_REVIEW",json.readTree(send(admin,"POST",c+"/review","{\"expectedVersion\":0}").body()).get("status").asString());
        assertEquals("409 VERSION_CONFLICT",outcome(send(admin,"POST",c+"/review","{\"expectedVersion\":0}")));
        assertEquals("400 VALIDATION_FAILED",outcome(send(admin,"POST",c+"/resolve","{\"expectedVersion\":1,\"outcome\":\"OPEN\",\"note\":\"Internal note text\"}")));
        var resolved=json.readTree(send(admin,"POST",c+"/resolve","{\"expectedVersion\":1,\"outcome\":\"DISMISSED\",\"note\":\"Internal note text\"}").body());
        assertEquals("DISMISSED",resolved.get("status").asString());
        assertEquals("Internal note text",resolved.get("adminNote").asString());
        assertEquals("409 COMPLAINT_STATE_CONFLICT",outcome(send(admin,"POST",c+"/resolve","{\"expectedVersion\":2,\"outcome\":\"ACTION_TAKEN\",\"note\":\"Second try note\"}")));
        var mine=json.readTree(send(reporter,"GET","/api/v1/complaints/"+id,null).body());
        assertEquals("DISMISSED",mine.get("status").asString());
        assertFalse(mine.has("adminNote") || mine.has("reviewerUserId"),"the reporter never sees the internal note or the admin");
        assertEquals(List.of("COMPLAINT_REVIEW_STARTED","COMPLAINT_DISMISSED"),
            jdbc.queryForList("SELECT action FROM admin_audit_log WHERE target_id=? ORDER BY created_at",String.class,id));

        // At most 20 open complaints per reporter.
        for(int i=0;i<20;i++) jdbc.update("INSERT INTO complaints(id,reporter_user_id,company_id,reason,description,status,created_at,version)"
            +" VALUES(UUID(),?,?,'OTHER','Synthetic open complaint','OPEN',UTC_TIMESTAMP(6),0)",stranger.id().toString(),o.toString());
        assertEquals("409 COMPLAINT_LIMIT_REACHED",outcome(send(stranger,"POST","/api/v1/complaints",complaint(s,null,"One more complaint over the limit."))));
    }

    @Test void suspensionsHideAndBlockAndCanBeAppealed() throws Exception {
        var seller=user(); UUID s=company(seller); var buyer=user(); UUID b=company(buyer); var newcomer=user(); UUID n=company(newcomer);
        jdbc.update("UPDATE companies SET selling_enabled=TRUE WHERE id=?",s.toString()); // verified seller, as in production
        assertEquals(200,send(seller,"PUT","/api/v1/companies/"+s+"/seller-profile","{\"listed\":true}").statusCode());
        String relation=json.readTree(send(buyer,"POST","/api/v1/companies/"+b+"/trade-relationships","{\"sellerCompanyId\":\""+s+"\"}").body()).get("id").asString();
        assertEquals(200,send(seller,"POST","/api/v1/companies/"+s+"/trade-relationships/"+relation+"/approve","{\"expectedVersion\":0}").statusCode());
        var p=json.readTree(send(seller,"POST","/api/v1/companies/"+s+"/products",
            "{\"sku\":\"SUS-1\",\"name\":\"Suspended Goods\",\"price\":{\"amount\":\"1\",\"currency\":\"TRY\",\"taxIncluded\":false}}").body());
        String pc="/api/v1/companies/"+s+"/products/"+p.get("id").asString();
        long v=json.readTree(send(seller,"PUT",pc+"/buyers","{\"expectedVersion\":0,\"buyerCompanyIds\":[\""+b+"\"]}").body()).get("productVersion").asLong();
        v=json.readTree(send(seller,"POST",pc+"/publish","{\"expectedVersion\":"+v+"}").body()).get("version").asLong();
        assertEquals(200,send(seller,"PUT",pc+"/public-visibility","{\"expectedVersion\":"+v+",\"publicVisible\":true}").statusCode());
        String pub="/api/v1/catalog/products/"+p.get("id").asString(), buyerList="/api/v1/companies/"+b+"/catalog/sellers/"+s+"/products";
        assertEquals(200,request(null,"GET",pub,null,null).statusCode());

        var admin=login(admin());
        assertEquals(400,send(admin,"POST","/api/v1/admin/companies/"+s+"/suspension","{\"reason\":\"FRAUD\",\"statement\":\"short\"}").statusCode());
        var suspended=send(admin,"POST","/api/v1/admin/companies/"+s+"/suspension","{\"reason\":\"FRAUD\",\"statement\":\""+STATEMENT+"\"}");
        assertEquals(200,suspended.statusCode(),suspended.body());
        assertEquals("409 ALREADY_SUSPENDED",outcome(send(admin,"POST","/api/v1/admin/companies/"+s+"/suspension","{\"reason\":\"FRAUD\",\"statement\":\""+STATEMENT+"\"}")));

        assertEquals("403 COMPANY_SUSPENDED",outcome(send(seller,"GET","/api/v1/companies/"+s+"/products",null)));
        assertTrue(json.readTree(send(seller,"GET","/api/v1/companies/"+s,null).body()).get("suspended").asBoolean(),"members still see their company and its state");
        assertEquals(404,request(null,"GET",pub,null,null).statusCode());
        assertEquals(404,request(null,"GET","/api/v1/sellers/"+s,null,null).statusCode());
        assertEquals(0,json.readTree(request(null,"GET","/api/v1/catalog/products?sellerCompanyId="+s,null,null).body()).get("totalElements").asLong());
        assertEquals(0,json.readTree(send(buyer,"GET",buyerList,null).body()).get("totalElements").asLong());
        assertEquals("404 SELLER_NOT_FOUND",outcome(send(newcomer,"POST","/api/v1/companies/"+n+"/trade-relationships","{\"sellerCompanyId\":\""+s+"\"}")));

        // Statement of reasons by mail with a single-use appeal code.
        String mail=latestMail(seller.email());
        assertTrue(mail.contains("FRAUD") && mail.contains(STATEMENT),mail);
        String code=lastLine(mail);
        assertEquals("400 INVALID_TOKEN",outcome(send(null,"POST","/api/v1/moderation/appeals",appeal("A".repeat(43)))));
        var appealed=send(newcomer,"POST","/api/v1/moderation/appeals",appeal(code));
        assertEquals(201,appealed.statusCode(),appealed.body());
        assertEquals("400 INVALID_TOKEN",outcome(send(newcomer,"POST","/api/v1/moderation/appeals",appeal(code))),"one appeal per decision");
        String appealId=json.readTree(appealed.body()).get("id").asString();
        assertEquals(200,send(admin,"POST","/api/v1/admin/appeals/"+appealId+"/decide","{\"expectedVersion\":0,\"outcome\":\"REVERSED\",\"note\":\""+STATEMENT+"\"}").statusCode());
        assertEquals("409 APPEAL_STATE_CONFLICT",outcome(send(admin,"POST","/api/v1/admin/appeals/"+appealId+"/decide","{\"expectedVersion\":1,\"outcome\":\"UPHELD\",\"note\":\""+STATEMENT+"\"}")));
        assertEquals(200,request(null,"GET",pub,null,null).statusCode(),"reversal restores public visibility");
        assertEquals(1,json.readTree(send(buyer,"GET",buyerList,null).body()).get("totalElements").asLong());
        assertEquals(200,send(seller,"GET","/api/v1/companies/"+s+"/products",null).statusCode());
        var history=json.readTree(send(admin,"GET","/api/v1/admin/companies/"+s,null).body()).get("restrictions");
        assertEquals("RESTORE",history.get(0).get("action").asString()); assertEquals("SUSPEND",history.get(1).get("action").asString());

        // User suspension: sessions end, login is refused only after the right password.
        assertEquals("409 TARGET_IS_ADMIN",outcome(send(admin,"POST","/api/v1/admin/users/"+admin.id()+"/suspension","{\"reason\":\"OTHER\",\"statement\":\""+STATEMENT+"\"}")));
        assertEquals(200,send(admin,"POST","/api/v1/admin/users/"+buyer.id()+"/suspension","{\"reason\":\"TERMS_VIOLATION\",\"statement\":\""+STATEMENT+"\"}").statusCode());
        assertEquals(401,send(buyer,"GET","/api/v1/auth/me",null).statusCode(),"existing sessions are gone");
        assertEquals("403 ACCOUNT_SUSPENDED",outcome(loginResponse(client(),buyer.email(),PASSWORD,null)));
        assertEquals("401 INVALID_CREDENTIALS",outcome(loginResponse(client(),buyer.email(),"Wrong-Password-123!",null)),"no leak without the password");
        String userCode=lastLine(latestMail(buyer.email()));
        assertEquals(200,send(admin,"POST","/api/v1/admin/users/"+buyer.id()+"/restore","{\"statement\":\""+STATEMENT+"\"}").statusCode());
        assertEquals("400 INVALID_TOKEN",outcome(send(null,"POST","/api/v1/moderation/appeals",appeal(userCode))),"no appeal against a lifted decision");
        assertEquals(204,loginResponse(client(),buyer.email(),PASSWORD,null).statusCode());
        assertEquals(List.of("USER_SUSPENDED","USER_RESTORED"),
            jdbc.queryForList("SELECT action FROM admin_audit_log WHERE target_id=? ORDER BY created_at",String.class,buyer.id().toString()));
    }

    /** ADR-011 with the production setting: a new company sells only once a platform admin has verified its billing details. */
    @Test void sellersSellOnlyAfterAPlatformAdminVerifiesThem() throws Exception {
        var seller=user(); UUID s=company(seller); String c="/api/v1/companies/"+s;
        var profile=json.readTree(send(seller,"GET",c+"/profile",null).body());
        assertEquals("NONE",profile.get("sellerStatus").asString());
        assertFalse(profile.get("sellingEnabled").asBoolean(),"a new company cannot sell before verification");
        assertEquals(200,send(seller,"PUT",c+"/seller-profile","{\"listed\":true}").statusCode());
        var p=json.readTree(send(seller,"POST",c+"/products",
            "{\"sku\":\"VER-1\",\"name\":\"Verified Goods\",\"price\":{\"amount\":\"1\",\"currency\":\"TRY\",\"taxIncluded\":false}}").body());
        String pc=c+"/products/"+p.get("id").asString(), pub="/api/v1/catalog/products/"+p.get("id").asString();
        long v=json.readTree(send(seller,"POST",pc+"/publish","{\"expectedVersion\":0}").body()).get("version").asLong();
        assertEquals(200,send(seller,"PUT",pc+"/public-visibility","{\"expectedVersion\":"+v+",\"publicVisible\":true}").statusCode());
        assertEquals("404 PRODUCT_NOT_FOUND",outcome(request(null,"GET",pub,null,null)),"an unverified seller's products stay hidden");
        assertEquals("404 SELLER_NOT_FOUND",outcome(request(null,"GET","/api/v1/sellers/"+s,null,null)));
        assertTrue(json.readTree(send(seller,"GET",c+"/seller-profile",null).body()).get("listed").asBoolean(),"the owner still sees the switch");

        String billing="\"legalName\":\"Synthetic Ticaret A.S.\",\"taxId\":\"%s\",\"taxOffice\":\"Kadikoy\",\"address\":\"Synthetic street 1\",\"city\":\"Istanbul\",\"phone\":\"+905551112233\",\"iban\":\"%s\"";
        String iban="TR18 0006 2001 1900 0006 6723 15";
        String edit="{\"billing\":{"+billing+"},\"description\":\"%s\",\"website\":\"https://example.invalid\"}";
        assertEquals("409 PROFILE_INCOMPLETE",outcome(send(seller,"POST",c+"/seller-application",null)));
        assertEquals("400 TAX_ID_INVALID",outcome(send(seller,"PUT",c+"/profile",edit.formatted("1234567891",iban,"Wrong check digit"))));
        assertEquals("400 REQUEST_INVALID",outcome(send(seller,"PUT",c+"/profile",edit.formatted("12ab567890",iban,"Not digits"))));
        assertEquals("400 TAX_ID_INVALID",outcome(send(seller,"PUT",c+"/profile",edit.formatted("10000000147",iban,"TCKN wrong check digit"))));
        assertEquals(200,send(seller,"PUT",c+"/profile",edit.formatted("10000000146",iban,"Sole trader TCKN")).statusCode());
        assertEquals("400 IBAN_INVALID",outcome(send(seller,"PUT",c+"/profile",edit.formatted("1234567890","TR19 0006 2001 1900 0006 6723 15","Wrong IBAN check digits"))));
        // No IBAN: the profile saves, but the application is incomplete.
        assertEquals(200,send(seller,"PUT",c+"/profile",edit.formatted("1234567890","","Wholesale since 1990")).statusCode());
        assertEquals("409 PROFILE_INCOMPLETE",outcome(send(seller,"POST",c+"/seller-application",null)));
        assertEquals(200,send(seller,"PUT",c+"/profile",edit.formatted("1234567890",iban,"Wholesale since 1990")).statusCode());
        var logo=upload(seller,c+"/logo",com.ticarvo.shared.storage.ImagesTest.encode("png",800,400));
        assertEquals(200,logo.statusCode(),logo.body());
        assertTrue(json.readTree(logo.body()).get("hasLogo").asBoolean());
        assertEquals("image/png",request(seller,"GET",c+"/logo",null,null).headers().firstValue("Content-Type").orElseThrow());
        assertEquals("404 SELLER_NOT_FOUND",outcome(request(null,"GET","/api/v1/sellers/"+s+"/logo",null,null)));

        assertEquals("PENDING",json.readTree(send(seller,"POST",c+"/seller-application",null).body()).get("sellerStatus").asString());
        assertEquals("409 SELLER_STATUS_CONFLICT",outcome(send(seller,"POST",c+"/seller-application",null)));
        assertEquals("409 BILLING_LOCKED",outcome(send(seller,"PUT",c+"/profile",edit.formatted("10000000146",iban,"Swap the tax id"))));
        assertEquals("409 BILLING_LOCKED",outcome(send(seller,"PUT",c+"/profile",edit.formatted("1234567890","TR33 0006 1005 1978 6457 8413 26","Swap the IBAN"))));
        assertEquals(200,send(seller,"PUT",c+"/profile",edit.formatted("1234567890",iban,"The showcase stays editable")).statusCode());
        String decision="/api/v1/admin/companies/"+s+"/seller-decision";
        assertEquals("403 PLATFORM_ADMIN_REQUIRED",outcome(send(seller,"POST",decision,"{\"outcome\":\"VERIFIED\"}")));

        var admin=login(admin());
        var queue=json.readTree(send(admin,"GET","/api/v1/admin/seller-applications?size=100",null).body()).get("content");
        assertTrue(queue.toString().contains(s.toString()),"the application is in the admin queue");
        assertEquals("1234567890",json.readTree(send(admin,"GET","/api/v1/admin/companies/"+s+"/seller",null).body()).get("billing").get("taxId").asString());
        assertEquals("400 VALIDATION_FAILED",outcome(send(admin,"POST",decision,"{\"outcome\":\"REJECTED\"}")),"a rejection needs a reason");
        assertEquals("400 VALIDATION_FAILED",outcome(send(admin,"POST",decision,"{\"outcome\":\"PENDING\"}")));
        var rejected=json.readTree(send(admin,"POST",decision,"{\"outcome\":\"REJECTED\",\"note\":\"Tax office does not match.\"}").body());
        assertEquals("REJECTED",rejected.get("sellerStatus").asString());
        assertEquals("Tax office does not match.",json.readTree(send(seller,"GET",c+"/profile",null).body()).get("sellerNote").asString());
        assertEquals("409 SELLER_STATUS_CONFLICT",outcome(send(admin,"POST",decision,"{\"outcome\":\"VERIFIED\"}")),"nothing to decide until it applies again");
        assertEquals("PENDING",json.readTree(send(seller,"POST",c+"/seller-application",null).body()).get("sellerStatus").asString());
        var verified=json.readTree(send(admin,"POST",decision,"{\"outcome\":\"VERIFIED\"}").body());
        assertTrue(verified.get("sellingEnabled").asBoolean());

        assertEquals(200,request(null,"GET",pub,null,null).statusCode(),"verified: the product is public");
        var publicSeller=json.readTree(request(null,"GET","/api/v1/sellers/"+s,null,null).body());
        assertTrue(publicSeller.get("verified").asBoolean() && publicSeller.get("hasLogo").asBoolean());
        assertEquals("Istanbul",publicSeller.get("city").asString());
        assertEquals(200,request(null,"GET","/api/v1/sellers/"+s+"/logo",null,null).statusCode());
        assertEquals("409 BILLING_LOCKED",outcome(send(seller,"PUT",c+"/profile",edit.formatted("10000000146",iban,"Still locked"))));

        // Revoking a verification stops the selling again.
        assertEquals("REJECTED",json.readTree(send(admin,"POST",decision,"{\"outcome\":\"REJECTED\",\"note\":\"Verification revoked.\"}").body()).get("sellerStatus").asString());
        assertEquals(404,request(null,"GET",pub,null,null).statusCode());
        assertEquals(List.of("SELLER_REJECTED","SELLER_VERIFIED","SELLER_REJECTED"),
            jdbc.queryForList("SELECT action FROM admin_audit_log WHERE target_id=? ORDER BY created_at",String.class,s.toString()));
        assertEquals(200,send(seller,"DELETE",c+"/logo",null).statusCode());
        assertEquals("404 LOGO_NOT_FOUND",outcome(request(seller,"GET",c+"/logo",null,null)));
    }
    HttpResponse<String> upload(User user,String path,byte[] file) throws Exception {
        String boundary="ticarvo-"+UUID.randomUUID();
        var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(file);
        body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .header("Content-Type","multipart/form-data; boundary="+boundary).header("X-CSRF-TOKEN",csrf(user));
        return user.http().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    Admin admin() throws Exception {
        var user=user();
        String secret=json.readTree(send(user,"POST","/api/v1/auth/mfa/setup","").body()).get("secret").asString();
        var codes=new ArrayDeque<String>();
        for(JsonNode node:json.readTree(send(user,"POST","/api/v1/auth/mfa/enable","{\"code\":\""+MfaIntegrationTest.totp(secret)+"\"}").body()).get("recoveryCodes")) codes.add(node.asString());
        admins.grant(user.email());
        return new Admin(user,user.email(),codes);
    }
    /** Recovery codes are single-use second factors, so no test waits for the next TOTP step. */
    User login(Admin admin) throws Exception {
        var client=client();
        assertEquals(204,loginResponse(client,admin.email(),PASSWORD,admin.recoveryCodes().pop()).statusCode());
        return new User(admin.session().id(),admin.email(),client);
    }
    User user() throws Exception {
        var created=identity.register(new RegisterRequest("m4a-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M4A"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",created.id().toString());
        var client=client();
        assertEquals(204,loginResponse(client,created.email(),PASSWORD,null).statusCode());
        return new User(created.id(),created.email(),client);
    }
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> loginResponse(HttpClient client,String email,String password,String code) throws Exception {
        String form="email="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)
            +(code==null?"":"&code="+URLEncoder.encode(code,StandardCharsets.UTF_8));
        return request(new User(null,email,client),"POST","/api/v1/auth/login",form,"application/x-www-form-urlencoded");
    }
    UUID company(User owner) throws Exception {
        return UUID.fromString(json.readTree(send(owner,"POST","/api/v1/companies","{\"name\":\"M4A "+UUID.randomUUID()+"\"}").body()).get("id").asString());
    }
    static String complaint(UUID company,String product,String description) {
        return "{\"companyId\":\""+company+"\""+(product==null?"":",\"productId\":\""+product+"\"")+",\"reason\":\"COUNTERFEIT\",\"description\":\""+description+"\"}";
    }
    static String appeal(String code) { return "{\"token\":\""+code+"\",\"text\":\"Synthetic appeal text explaining the mistake.\"}"; }
    String latestMail(String email) throws IOException {
        try(var files=Files.list(OUTBOX)) {
            var mail=files.filter(file->read(file).startsWith("To: "+email+"\n")).sorted().toList().getLast();
            return read(mail);
        }
    }
    static String lastLine(String mail) { var lines=mail.strip().split("\n"); return lines[lines.length-1]; }
    static String read(Path file) { try { return Files.readString(file); } catch(IOException failure) { throw new IllegalStateException(failure); } }
    String outcome(HttpResponse<String> response) {
        if(response.body()==null || response.body().isBlank()) return Integer.toString(response.statusCode());
        var code=json.readTree(response.body()).get("code");
        return code==null ? Integer.toString(response.statusCode()) : response.statusCode()+" "+code.asString();
    }
    HttpResponse<String> send(User user,String method,String path,String body) throws Exception { return request(user,method,path,body,"application/json"); }
    String csrf(User user) throws Exception { return json.readTree(request(user,"GET","/api/v1/auth/csrf",null,null).body()).get("token").asString(); }
    HttpResponse<String> request(User user,String method,String path,String body,String type) throws Exception { return request(user,method,path,body,type,true); }
    HttpResponse<String> request(User user,String method,String path,String body,String type,boolean withCsrf) throws Exception {
        var client=user==null?client():user.http();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(type!=null) builder.header("Content-Type",type);
        if(withCsrf && !method.equals("GET")) builder.header("X-CSRF-TOKEN",csrf(user==null?new User(null,null,client):user));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static Path tempDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required for real MySQL");
        return value;
    }
}
