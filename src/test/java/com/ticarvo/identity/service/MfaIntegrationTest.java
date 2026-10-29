package com.ticarvo.identity.service;
import com.ticarvo.testsupport.TestHttp;
import com.ticarvo.identity.dto.RegisterRequest;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-005 acceptance over real HTTP, MySQL and JDBC sessions. TOTP is computed independently of MfaService. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
public class MfaIntegrationTest {
    static final Path OUTBOX=tempDirectory();
    static final String PASSWORD="Synthetic-M42-Password!95";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",OUTBOX::toString);
    }
    @Autowired IdentityService identity;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;

    @Test void enrollmentAndLoginWithReplayProtection() throws Exception {
        String email=user(); var session=client();
        assertEquals("204",login(session,email,PASSWORD,null));
        assertEquals("409 MFA_SETUP_REQUIRED",outcome(post(session,"/api/v1/auth/mfa/enable","{\"code\":\"123456\"}")));
        assertEquals("409 MFA_NOT_ENABLED",outcome(post(session,"/api/v1/auth/mfa/disable","{\"code\":\"123456\"}")));
        var setup=json.readTree(post(session,"/api/v1/auth/mfa/setup","").body());
        String secret=setup.get("secret").asString();
        assertTrue(secret.matches("[A-Z2-7]{32}"));
        assertTrue(setup.get("otpauthUri").asString().startsWith("otpauth://totp/Ticarvo%3A")
            && setup.get("otpauthUri").asString().contains("secret="+secret+"&issuer=Ticarvo"));
        String stored=jdbc.queryForObject("SELECT mfa_secret FROM app_users WHERE email=?",String.class,email);
        assertFalse(stored.contains(secret),"secret is encrypted at rest");
        assertEquals("400 MFA_CODE_INVALID",outcome(post(session,"/api/v1/auth/mfa/enable","{\"code\":\""+wrongCode(secret)+"\"}")));
        String code=totp(secret);
        var enabled=post(session,"/api/v1/auth/mfa/enable","{\"code\":\""+code+"\"}");
        assertEquals(200,enabled.statusCode());
        var recovery=new HashSet<String>();
        for(JsonNode node:json.readTree(enabled.body()).get("recoveryCodes")) recovery.add(node.asString());
        assertEquals(10,recovery.size());
        assertTrue(recovery.stream().allMatch(value->value.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}")));
        assertTrue(json.readTree(get(session,"/api/v1/auth/me").body()).get("mfaEnabled").asBoolean());
        assertEquals("409 MFA_ALREADY_ENABLED",outcome(post(session,"/api/v1/auth/mfa/setup","")));

        assertEquals("401 MFA_REQUIRED",login(client(),email,PASSWORD,null));
        assertEquals("401 INVALID_CREDENTIALS",login(client(),email,"Wrong-Synthetic-Pass1",null),"no MFA oracle without the password");
        assertEquals("401 INVALID_CREDENTIALS",login(client(),email,"Wrong-Synthetic-Pass1",code));
        assertEquals("401 MFA_REQUIRED",login(client(),email,PASSWORD,code),"the code used to enable cannot log in again");
        allowStepReuse(email);
        var fresh=client(); String next=totp(secret);
        assertEquals("204",login(fresh,email,PASSWORD,next));
        assertEquals(200,get(fresh,"/api/v1/auth/me").statusCode());
        assertEquals("401 MFA_REQUIRED",login(client(),email,PASSWORD,next),"replayed code");
    }

    @Test void recoveryCodesWorkOnce() throws Exception {
        String email=user(); var session=client();
        assertEquals("204",login(session,email,PASSWORD,null));
        String secret=json.readTree(post(session,"/api/v1/auth/mfa/setup","").body()).get("secret").asString();
        var codes=new ArrayList<String>();
        for(JsonNode node:json.readTree(post(session,"/api/v1/auth/mfa/enable","{\"code\":\""+totp(secret)+"\"}").body()).get("recoveryCodes")) codes.add(node.asString());
        assertEquals("204",login(client(),email,PASSWORD,codes.get(0)));
        assertEquals("401 MFA_REQUIRED",login(client(),email,PASSWORD,codes.get(0)),"recovery code is single use");
        assertEquals("204",login(client(),email,PASSWORD,codes.get(1).replace("-","").toLowerCase(Locale.ROOT)),"dashes and case ignored");
        assertEquals(8L,recoveryRows(email));
    }

    @Test void codeAttemptsAreLimitedPerAccount() throws Exception {
        String email=user(); var session=client();
        assertEquals("204",login(session,email,PASSWORD,null));
        String secret=json.readTree(post(session,"/api/v1/auth/mfa/setup","").body()).get("secret").asString();
        assertEquals(200,post(session,"/api/v1/auth/mfa/enable","{\"code\":\""+totp(secret)+"\"}").statusCode());
        for(int i=0;i<4;i++) assertEquals("400 MFA_CODE_INVALID",outcome(post(session,"/api/v1/auth/mfa/disable","{\"code\":\""+wrongCode(secret)+"\"}")));
        assertEquals("429 RATE_LIMITED",outcome(post(session,"/api/v1/auth/mfa/disable","{\"code\":\""+wrongCode(secret)+"\"}")));
        allowStepReuse(email);
        var blocked=loginResponse(client(),email,PASSWORD,totp(secret));
        assertEquals("429 MFA_RATE_LIMITED",outcome(blocked),"even a correct code is refused after the limit");
        assertEquals("900",blocked.headers().firstValue("Retry-After").orElse(""));
    }

    @Test void passwordResetKeepsMfaAndDisableTurnsItOff() throws Exception {
        String email=user(); var session=client();
        assertEquals("204",login(session,email,PASSWORD,null));
        String secret=json.readTree(post(session,"/api/v1/auth/mfa/setup","").body()).get("secret").asString();
        assertEquals(200,post(session,"/api/v1/auth/mfa/enable","{\"code\":\""+totp(secret)+"\"}").statusCode());
        accounts.requestPasswordReset(email);
        accounts.resetPassword(latestToken(email),"New-Synthetic-M42-Pass!7");
        assertEquals(401,get(session,"/api/v1/auth/me").statusCode(),"reset ended the session");
        assertEquals("401 MFA_REQUIRED",login(client(),email,"New-Synthetic-M42-Pass!7",null),"reset does not remove MFA");
        allowStepReuse(email);
        var again=client();
        assertEquals("204",login(again,email,"New-Synthetic-M42-Pass!7",totp(secret)));
        allowStepReuse(email);
        assertEquals("204",outcome(post(again,"/api/v1/auth/mfa/disable","{\"code\":\""+totp(secret)+"\"}")));
        assertFalse(json.readTree(get(again,"/api/v1/auth/me").body()).get("mfaEnabled").asBoolean());
        assertEquals(0L,recoveryRows(email));
        assertNull(jdbc.queryForObject("SELECT mfa_secret FROM app_users WHERE email=?",String.class,email));
        assertEquals("204",login(client(),email,"New-Synthetic-M42-Pass!7",null));
    }

    String user() {
        var created=identity.register(new RegisterRequest("m42-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M42"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",created.id().toString());
        return created.email();
    }
    /** Simulates the next 30-second step so a test does not have to wait. */
    void allowStepReuse(String email) { jdbc.update("UPDATE app_users SET mfa_last_step=NULL WHERE email=?",email); }
    long recoveryRows(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens t JOIN app_users u ON u.id=t.user_id WHERE u.email=? AND t.purpose='MFA_RECOVERY'",Long.class,email);
    }
    String latestToken(String email) throws IOException {
        try(var files=Files.list(OUTBOX)) {
            var mail=files.filter(file->read(file).startsWith("To: "+email+"\n")).sorted().toList().getLast();
            var lines=read(mail).strip().split("\n");
            return lines[lines.length-1];
        }
    }
    /** Independent RFC 6238 implementation (HMAC-SHA1, 6 digits, 30 s). */
    public static String totp(String base32Secret) throws Exception {
        return totpAt(decodeBase32(base32Secret),System.currentTimeMillis()/30_000);
    }
    static String totpAt(byte[] secret,long step) throws Exception {
        var mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret,"HmacSHA1"));
        byte[] h=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int o=h[19]&15;
        int value=((h[o]&127)<<24|(h[o+1]&255)<<16|(h[o+2]&255)<<8|(h[o+3]&255))%1_000_000;
        return String.format(Locale.ROOT,"%06d",value);
    }
    /** A 6-digit code that is not valid in any accepted step. */
    static String wrongCode(String base32Secret) throws Exception {
        byte[] secret=decodeBase32(base32Secret); long now=System.currentTimeMillis()/30_000;
        var valid=Set.of(totpAt(secret,now-1),totpAt(secret,now),totpAt(secret,now+1),totpAt(secret,now+2));
        for(int i=0;;i++){String candidate=String.format(Locale.ROOT,"%06d",i);if(!valid.contains(candidate))return candidate;}
    }
    static byte[] decodeBase32(String value) {
        var out=new java.io.ByteArrayOutputStream(); int buffer=0,bits=0;
        for(char c:value.toCharArray()){buffer=(buffer<<5)|"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);bits+=5;
            if(bits>=8){out.write((buffer>>(bits-8))&255);bits-=8;}}
        return out.toByteArray();
    }
    String login(HttpClient client,String email,String password,String code) throws Exception { return outcome(loginResponse(client,email,password,code)); }
    HttpResponse<String> loginResponse(HttpClient client,String email,String password,String code) throws Exception {
        String form="email="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)
            +(code==null?"":"&code="+URLEncoder.encode(code,StandardCharsets.UTF_8));
        return request(client,"POST","/api/v1/auth/login",form,csrf(client),"application/x-www-form-urlencoded");
    }
    HttpResponse<String> post(HttpClient client,String path,String body) throws Exception { return request(client,"POST",path,body,csrf(client),"application/json"); }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return request(client,"GET",path,null,null,null); }
    String outcome(HttpResponse<String> response) { return TestHttp.outcome(json,response); }
    String csrf(HttpClient client) throws Exception { return json.readTree(get(client,"/api/v1/auth/csrf").body()).get("token").asString(); }
    HttpResponse<String> request(HttpClient client,String method,String path,String body,String csrf,String type) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(type!=null) builder.header("Content-Type",type);
        if(csrf!=null) builder.header("X-CSRF-TOKEN",csrf);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient client() { return TestHttp.client(); }
    static String read(Path file) { return TestHttp.read(file); }
    static Path tempDirectory() { return TestHttp.tempDirectory("ticarvo-mfa-outbox"); }
    static String required(String key) { return TestHttp.required(key); }
}
