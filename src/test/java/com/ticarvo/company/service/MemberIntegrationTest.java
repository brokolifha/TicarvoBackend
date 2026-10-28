package com.ticarvo.company.service;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.service.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

/** ADR-006 role matrix, invitations and ownership over real HTTP, MySQL and JDBC sessions. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MemberIntegrationTest {
    static final Path OUTBOX=tempDirectory();
    static final String PASSWORD="Synthetic-M43-Password!95";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",OUTBOX::toString);
    }
    @Autowired IdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    record User(UUID id,String email,HttpClient http) {}
    record Team(UUID company,User owner,User admin,User member,User outsider) {}

    @Test void permissionMatrixMatchesAdr006() throws Exception {
        var t=team();
        String c="/api/v1/companies/"+t.company();
        // Everyone in the company can see members, relationships and the seller profile; outsiders get 404.
        var members=json.readTree(get(t.member(),c+"/members").body());
        assertEquals(List.of("OWNER","ADMIN","MEMBER"),roles(members));
        for(User u:List.of(t.owner(),t.admin(),t.member())) {
            assertEquals(200,get(u,c+"/trade-relationships?direction=INCOMING").statusCode());
            assertEquals(200,get(u,c+"/seller-profile").statusCode());
        }
        assertEquals("404 COMPANY_NOT_FOUND",outcome(get(t.outsider(),c+"/members")));
        // Catalog: every member.
        for(User u:List.of(t.owner(),t.admin(),t.member())) {
            assertEquals(201,send(u,"POST",c+"/products","{\"sku\":\"M43-"+UUID.randomUUID().toString().substring(0,8)+"\",\"name\":\"x\",\"price\":{\"amount\":\"1\",\"currency\":\"TRY\",\"taxIncluded\":false}}").statusCode());
        }
        // Commercial and external decisions: ADMIN and above.
        assertEquals("403 ADMIN_REQUIRED",outcome(send(t.member(),"PUT",c+"/seller-profile","{\"listed\":true}")));
        assertEquals("403 ADMIN_REQUIRED",outcome(send(t.member(),"POST",c+"/trade-invitation","")));
        assertEquals(200,send(t.admin(),"PUT",c+"/seller-profile","{\"listed\":true}").statusCode());
        assertEquals(201,send(t.admin(),"POST",c+"/trade-invitation","").statusCode());
        UUID outsiderCompany=company(t.outsider());
        var applied=send(t.outsider(),"POST","/api/v1/companies/"+outsiderCompany+"/trade-relationships","{\"sellerCompanyId\":\""+t.company()+"\"}");
        String relation=json.readTree(applied.body()).get("id").asString();
        assertEquals("403 ADMIN_REQUIRED",outcome(send(t.member(),"POST",c+"/trade-relationships/"+relation+"/approve","{\"expectedVersion\":0}")));
        assertEquals(200,send(t.admin(),"POST",c+"/trade-relationships/"+relation+"/approve","{\"expectedVersion\":0}").statusCode());
        assertEquals("403 ADMIN_REQUIRED",outcome(send(t.member(),"POST",c+"/trade-relationships","{\"sellerCompanyId\":\""+t.company()+"\"}")));
        // Team management.
        assertEquals("403 ADMIN_REQUIRED",outcome(send(t.member(),"POST",c+"/member-invitations",invite("x-"+UUID.randomUUID()+"@example.invalid","MEMBER"))));
        assertEquals("403 ADMIN_REQUIRED",outcome(get(t.member(),c+"/member-invitations")));
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.admin(),"POST",c+"/member-invitations",invite("y-"+UUID.randomUUID()+"@example.invalid","ADMIN"))));
        assertEquals(201,send(t.admin(),"POST",c+"/member-invitations",invite("z-"+UUID.randomUUID()+"@example.invalid","MEMBER")).statusCode());
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.admin(),"PUT",c+"/members/"+t.member().id(),"{\"role\":\"ADMIN\"}")));
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.admin(),"POST",c+"/ownership-transfer","{\"userId\":\""+t.admin().id()+"\"}")));
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.member(),"DELETE",c+"/members/"+t.admin().id(),null)),"only the owner removes an admin");
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.admin(),"DELETE",c+"/members/"+t.owner().id(),null)),"nobody else can remove the owner");
        assertEquals(200,send(t.owner(),"PUT",c+"/members/"+t.member().id(),"{\"role\":\"ADMIN\"}").statusCode());
        assertEquals(200,send(t.owner(),"PUT",c+"/members/"+t.member().id(),"{\"role\":\"MEMBER\"}").statusCode());
        assertEquals("409 OWNER_ROLE_FIXED",outcome(send(t.owner(),"PUT",c+"/members/"+t.owner().id(),"{\"role\":\"MEMBER\"}")));
        assertEquals(204,send(t.admin(),"DELETE",c+"/members/"+t.member().id(),null).statusCode(),"admins remove members");
        assertEquals("404 COMPANY_NOT_FOUND",outcome(get(t.member(),c+"/members")),"removal ends access immediately");
    }

    @Test void invitationCodesAreBoundToTheInvitedAccountAndSingleUse() throws Exception {
        User owner=user(); UUID company=company(owner); String c="/api/v1/companies/"+company;
        User invitee=user(), other=user();
        assertEquals(201,send(owner,"POST",c+"/member-invitations",invite(invitee.email().toUpperCase(Locale.ROOT),"MEMBER")).statusCode());
        String first=latestToken(invitee.email());
        assertEquals(201,send(owner,"POST",c+"/member-invitations",invite(invitee.email(),"MEMBER")).statusCode());
        String second=latestToken(invitee.email());
        assertEquals(1,json.readTree(get(owner,c+"/member-invitations").body()).size(),"re-invite replaces the pending one");
        assertEquals("404 INVITATION_NOT_FOUND",outcome(accept(invitee,first)),"replaced code is dead");
        assertEquals("404 INVITATION_NOT_FOUND",outcome(accept(other,second)),"code only works for the invited address");
        var accepted=accept(invitee,second);
        assertEquals(200,accepted.statusCode());
        assertEquals("MEMBER",json.readTree(accepted.body()).get("role").asString());
        assertEquals("404 INVITATION_NOT_FOUND",outcome(accept(invitee,second)),"single use");
        assertEquals("409 ALREADY_MEMBER",outcome(send(owner,"POST",c+"/member-invitations",invite(invitee.email(),"ADMIN"))));
        assertEquals(201,send(owner,"POST",c+"/member-invitations",invite(other.email(),"ADMIN")).statusCode());
        String expired=latestToken(other.email());
        jdbc.update("UPDATE company_member_invitations SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE company_id=?",company.toString());
        assertEquals("404 INVITATION_NOT_FOUND",outcome(accept(other,expired)));
        String invitationId=json.readTree(get(owner,c+"/member-invitations").body()).get(0).get("id").asString();
        assertEquals(204,send(owner,"DELETE",c+"/member-invitations/"+invitationId,null).statusCode());
        assertEquals(0,json.readTree(get(owner,c+"/member-invitations").body()).size());
        // Seat limit: members + pending invitations <= 50.
        for(int i=0;i<48;i++) {
            jdbc.update("INSERT INTO company_member_invitations VALUES(UUID(),?,?,'MEMBER',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 DAY)",
                company.toString(),"seat-"+i+"@example.invalid",UUID.randomUUID().toString().replace("-","")+"00000000000000000000000000000000",owner.id().toString());
        }
        assertEquals("409 MEMBER_LIMIT_REACHED",outcome(send(owner,"POST",c+"/member-invitations",invite("one-more@example.invalid","MEMBER"))));
    }

    @Test void ownershipIsSingleAndTransferable() throws Exception {
        var t=team(); String c="/api/v1/companies/"+t.company();
        assertEquals("409 OWNER_MUST_TRANSFER",outcome(send(t.owner(),"DELETE",c+"/members/"+t.owner().id(),null)));
        assertEquals("409 TRANSFER_TARGET_NOT_ADMIN",outcome(send(t.owner(),"POST",c+"/ownership-transfer","{\"userId\":\""+t.member().id()+"\"}")));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE company_memberships SET role='OWNER' WHERE company_id=? AND user_id=?",
            t.company().toString(),t.admin().id().toString()),"database allows exactly one owner");
        assertEquals(204,send(t.owner(),"POST",c+"/ownership-transfer","{\"userId\":\""+t.admin().id()+"\"}").statusCode());
        var roles=new HashMap<String,String>();
        for(JsonNode m:json.readTree(get(t.member(),c+"/members").body())) roles.put(m.get("userId").asString(),m.get("role").asString());
        assertEquals("OWNER",roles.get(t.admin().id().toString()));
        assertEquals("ADMIN",roles.get(t.owner().id().toString()));
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.owner(),"PUT",c+"/members/"+t.member().id(),"{\"role\":\"ADMIN\"}")),"former owner is now only an admin");
        assertEquals("403 OWNER_REQUIRED",outcome(send(t.owner(),"DELETE",c+"/members/"+t.admin().id(),null)),"former owner cannot remove the new owner");
        assertEquals(204,send(t.member(),"DELETE",c+"/members/"+t.member().id(),null).statusCode(),"members may leave");
        assertEquals(204,send(t.admin(),"DELETE",c+"/members/"+t.owner().id(),null).statusCode(),"new owner removes the former owner");
        assertEquals("404 COMPANY_NOT_FOUND",outcome(get(t.owner(),c+"/members")));
    }

    Team team() throws Exception {
        User owner=user(), admin=user(), member=user(), outsider=user();
        UUID company=company(owner); String c="/api/v1/companies/"+company;
        assertEquals(201,send(owner,"POST",c+"/member-invitations",invite(admin.email(),"ADMIN")).statusCode());
        assertEquals(200,accept(admin,latestToken(admin.email())).statusCode());
        assertEquals(201,send(admin,"POST",c+"/member-invitations",invite(member.email(),"MEMBER")).statusCode());
        assertEquals(200,accept(member,latestToken(member.email())).statusCode());
        return new Team(company,owner,admin,member,outsider);
    }
    User user() throws Exception {
        var created=identity.register(new RegisterRequest("m43-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M43"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",created.id().toString());
        var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var user=new User(created.id(),created.email(),http);
        String form="email="+URLEncoder.encode(created.email(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8);
        assertEquals(204,request(user,"POST","/api/v1/auth/login",form,"application/x-www-form-urlencoded").statusCode());
        return user;
    }
    UUID company(User owner) throws Exception {
        return UUID.fromString(json.readTree(send(owner,"POST","/api/v1/companies","{\"name\":\"M43 "+UUID.randomUUID()+"\"}").body()).get("id").asString());
    }
    static String invite(String email,String role) { return "{\"email\":\""+email+"\",\"role\":\""+role+"\"}"; }
    HttpResponse<String> accept(User user,String token) throws Exception { return send(user,"POST","/api/v1/member-invitations/accept","{\"token\":\""+token+"\"}"); }
    static List<String> roles(JsonNode members) { var out=new ArrayList<String>(); for(JsonNode m:members) out.add(m.get("role").asString()); return out; }
    String latestToken(String email) throws IOException {
        try(var files=Files.list(OUTBOX)) {
            var mail=files.filter(file->read(file).startsWith("To: "+email+"\n")).sorted().toList().getLast();
            var lines=read(mail).strip().split("\n");
            // ADR-012: the mail links to the page that takes the code; the code is never part of the link.
            if(read(mail).contains("Subject: Ticarvo firma daveti")) assertTrue(read(mail).contains("http://localhost:5173/panel/davet sayfasında"),read(mail));
            return lines[lines.length-1];
        }
    }
    String outcome(HttpResponse<String> response) {
        if(response.body()==null || response.body().isBlank()) return Integer.toString(response.statusCode());
        var code=json.readTree(response.body()).get("code");
        return code==null ? Integer.toString(response.statusCode()) : response.statusCode()+" "+code.asString();
    }
    HttpResponse<String> get(User user,String path) throws Exception { return request(user,"GET",path,null,null); }
    HttpResponse<String> send(User user,String method,String path,String body) throws Exception {
        return request(user,method,path,body==null?"":body,body==null?null:"application/json");
    }
    HttpResponse<String> request(User user,String method,String path,String body,String type) throws Exception {
        String csrf=method.equals("GET")?null:json.readTree(request(user,"GET","/api/v1/auth/csrf",null,null).body()).get("token").asString();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(type!=null) builder.header("Content-Type",type);
        if(csrf!=null) builder.header("X-CSRF-TOKEN",csrf);
        return user.http().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String read(Path file) {
        try { return Files.readString(file,StandardCharsets.UTF_8); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static Path tempDirectory() {
        try { return Files.createTempDirectory("ticarvo-member-outbox"); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required for real MySQL");
        return value;
    }
}
