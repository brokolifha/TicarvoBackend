package com.ticarvo.identity.service;
import com.ticarvo.shared.security.SecureTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-004 acceptance over real HTTP, MySQL and JDBC sessions. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AccountLifecycleIntegrationTest {
    static final Path OUTBOX=tempDirectory();
    static final String PASSWORD="Synthetic-M4-Password!95";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",OUTBOX::toString);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;

    @Test void loginRequiresVerifiedEmailAndTokensAreSingleUse() throws Exception {
        var client=client(); String email=register(client);
        assertEquals("401 INVALID_CREDENTIALS",login(client(),email,"Wrong-Synthetic-Pass1"),"no unverified-state oracle without the password");
        assertEquals("403 EMAIL_NOT_VERIFIED",login(client(),email,PASSWORD));
        assertEquals("400 TOKEN_INVALID",post(client,"/api/v1/auth/email-verification","{\"token\":\""+"A".repeat(43)+"\"}"));
        String token=latestToken(email);
        assertEquals("204",post(client,"/api/v1/auth/email-verification","{\"token\":\""+token+"\"}"));
        assertEquals("400 TOKEN_INVALID",post(client,"/api/v1/auth/email-verification","{\"token\":\""+token+"\"}"));
        assertEquals("204",login(client(),email,PASSWORD));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens WHERE token_hash=?",Long.class,SecureTokens.sha256Hex(token)),
            "consumed token row is gone");
    }

    @Test void resendHonoursCooldownReplacesOldTokenAndExpiryIsEnforced() throws Exception {
        var client=client(); String email=register(client); String first=latestToken(email);
        assertEquals("202",post(client,"/api/v1/auth/email-verification/resend","{\"email\":\""+email+"\"}"));
        assertEquals(1,mails(email).size(),"cooldown: no second mail within 60 seconds");
        jdbc.update("UPDATE account_tokens t JOIN app_users u ON u.id=t.user_id SET t.created_at=t.created_at-INTERVAL 2 MINUTE WHERE u.email=?",email);
        assertEquals("202",post(client,"/api/v1/auth/email-verification/resend","{\"email\":\""+email.toUpperCase(java.util.Locale.ROOT)+"\"}"));
        String second=latestToken(email);
        assertNotEquals(first,second);
        assertEquals("400 TOKEN_INVALID",post(client,"/api/v1/auth/email-verification","{\"token\":\""+first+"\"}"),"new token invalidates the old one");
        jdbc.update("UPDATE account_tokens t JOIN app_users u ON u.id=t.user_id SET t.expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE u.email=?",email);
        assertEquals("400 TOKEN_INVALID",post(client,"/api/v1/auth/email-verification","{\"token\":\""+second+"\"}"),"expired token");
        String unknown="nobody-"+UUID.randomUUID()+"@example.invalid";
        assertEquals("202",post(client,"/api/v1/auth/email-verification/resend","{\"email\":\""+unknown+"\"}"));
        assertEquals(0,mails(unknown).size());
    }

    @Test void passwordResetChangesPasswordAndEndsEverySession() throws Exception {
        var anonymous=client(); String email=verifiedUser(anonymous);
        var first=client(); var second=client();
        assertEquals("204",login(first,email,PASSWORD)); assertEquals("204",login(second,email,PASSWORD));
        assertEquals("202",post(anonymous,"/api/v1/auth/password-reset/request","{\"email\":\""+email+"\"}"));
        String token=latestToken(email);
        assertEquals("400 REQUEST_INVALID",post(anonymous,"/api/v1/auth/password-reset","{\"token\":\""+token+"\",\"newPassword\":\"short\"}"));
        assertEquals("204",post(anonymous,"/api/v1/auth/password-reset","{\"token\":\""+token+"\",\"newPassword\":\"New-Synthetic-M4-Pass!7\"}"));
        assertEquals(401,get(first,"/api/v1/auth/me").statusCode());
        assertEquals(401,get(second,"/api/v1/auth/me").statusCode());
        assertEquals("401 INVALID_CREDENTIALS",login(client(),email,PASSWORD));
        assertEquals("204",login(client(),email,"New-Synthetic-M4-Pass!7"));
        assertEquals("400 TOKEN_INVALID",post(anonymous,"/api/v1/auth/password-reset","{\"token\":\""+token+"\",\"newPassword\":\"Another-Synthetic-Pass!8\"}"));
        String unknown="nobody-"+UUID.randomUUID()+"@example.invalid";
        assertEquals("202",post(anonymous,"/api/v1/auth/password-reset/request","{\"email\":\""+unknown+"\"}"));
        assertEquals(0,mails(unknown).size());
    }

    @Test void revokeAllEndsOnlyThatUsersSessions() throws Exception {
        String a=verifiedUser(client()), b=verifiedUser(client());
        var a1=client(); var a2=client(); var b1=client();
        assertEquals("204",login(a1,a,PASSWORD)); assertEquals("204",login(a2,a,PASSWORD)); assertEquals("204",login(b1,b,PASSWORD));
        assertEquals(403,request(a1,"POST","/api/v1/auth/sessions/revoke-all","",null,"application/json").statusCode(),"CSRF required");
        assertEquals("204",post(a1,"/api/v1/auth/sessions/revoke-all",""));
        assertEquals(401,get(a1,"/api/v1/auth/me").statusCode());
        assertEquals(401,get(a2,"/api/v1/auth/me").statusCode());
        assertEquals(200,get(b1,"/api/v1/auth/me").statusCode());
        var anonymous=client();
        assertEquals(401,request(anonymous,"POST","/api/v1/auth/sessions/revoke-all","",csrf(anonymous),"application/json").statusCode(),
            "anonymous revoke-all rejected");
    }

    @Test void mailEndpointsAreRateLimitedPerAddress() throws Exception {
        var client=client(); String status="";
        for(int i=0;i<6 && !status.startsWith("429");i++) {
            status=post(client,"/api/v1/auth/password-reset/request","{\"email\":\"limit-"+i+"@example.invalid\"}");
        }
        assertEquals("429 RATE_LIMITED",status);
    }

    String register(HttpClient client) throws Exception {
        String email="m4-"+UUID.randomUUID()+"@example.invalid";
        assertEquals("201",post(client,"/api/v1/auth/register","{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\",\"displayName\":\"Synthetic M4\"}"));
        return email;
    }
    String verifiedUser(HttpClient client) throws Exception {
        String email=register(client);
        assertEquals("204",post(client,"/api/v1/auth/email-verification","{\"token\":\""+latestToken(email)+"\"}"));
        return email;
    }
    List<Path> mails(String email) throws IOException {
        try(var files=Files.list(OUTBOX)) {
            return files.filter(file->read(file).startsWith("To: "+email+"\n")).sorted().toList();
        }
    }
    String latestToken(String email) throws IOException {
        var all=mails(email);
        assertFalse(all.isEmpty(),"no mail for "+email);
        var lines=read(all.getLast()).strip().split("\n");
        String token=lines[lines.length-1];
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        return token;
    }
    String login(HttpClient client,String email,String password) throws Exception {
        var response=request(client,"POST","/api/v1/auth/login","email="+URLEncoder.encode(email,StandardCharsets.UTF_8)
            +"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8),csrf(client),"application/x-www-form-urlencoded");
        return outcome(response);
    }
    String post(HttpClient client,String path,String body) throws Exception {
        return outcome(request(client,"POST",path,body,csrf(client),"application/json"));
    }
    String outcome(HttpResponse<String> response) {
        if(response.body()==null || response.body().isBlank()) return Integer.toString(response.statusCode());
        var code=json.readTree(response.body()).get("code");
        return code==null ? Integer.toString(response.statusCode()) : response.statusCode()+" "+code.asString();
    }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return request(client,"GET",path,null,null,null); }
    String csrf(HttpClient client) throws Exception { return json.readTree(get(client,"/api/v1/auth/csrf").body()).get("token").asString(); }
    HttpResponse<String> request(HttpClient client,String method,String path,String body,String csrf,String type) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(type!=null) builder.header("Content-Type",type);
        if(csrf!=null) builder.header("X-CSRF-TOKEN",csrf);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
    static String read(Path file) {
        try { return Files.readString(file,StandardCharsets.UTF_8); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static Path tempDirectory() {
        try { return Files.createTempDirectory("ticarvo-mail-outbox"); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required for real MySQL");
        return value;
    }
}
