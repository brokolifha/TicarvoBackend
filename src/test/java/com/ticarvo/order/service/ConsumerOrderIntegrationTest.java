package com.ticarvo.order.service;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.service.IdentityService;
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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-010 consumer purchases: retail price, consents, privacy of the address, withdrawal and refunds, over real HTTP and MySQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ConsumerOrderIntegrationTest {
    static final String PASSWORD="Synthetic-M7-Password!95";
    static final String ADDRESS="{\"recipient\":\"Ayşe Yılmaz\",\"phone\":\"+90 555 111 22 33\",\"address\":\"Örnek Mah. Deneme Sok. No:1\",\"district\":\"Kadıköy\",\"city\":\"İstanbul\",\"postalCode\":\"34710\"}";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",()->tempDirectory("ticarvo-m7-outbox"));
        registry.add("ticarvo.storage.image-dir",()->tempDirectory("ticarvo-m7-images"));
    }
    @Autowired IdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    record User(UUID id,HttpClient http) {}

    @Test void consumersBuyPublicProductsAtTheRetailPriceAndTheAddressStaysPrivate() throws Exception {
        var seller=user(); UUID s=company(seller);
        String retail=product(seller,s,"RT-1","Kupa","50.00","119.90",true);
        String discoveryOnly=product(seller,s,"RT-2","Tabak","20.00",null,true);
        String privateOne=product(seller,s,"RT-3","Gizli","10.00","30.00",false);

        var pub=json.readTree(request(null,"GET","/api/v1/catalog/products/"+retail,null,null).body());
        assertEquals("119.90",pub.get("retailPrice").get("amount").asString());
        assertTrue(pub.get("retailPrice").get("taxIncluded").asBoolean(),"consumer prices always include tax");
        assertFalse(pub.toString().contains("50.00")||pub.has("sku")||pub.has("price"),"the B2B price and SKU stay private");
        assertTrue(json.readTree(request(null,"GET","/api/v1/catalog/products/"+discoveryOnly,null,null).body()).get("retailPrice").isNull());

        var consumer=user();
        String body=order(s,line(retail,2,"119.90"));
        assertEquals("400 VALIDATION_FAILED",outcome(place(consumer,null,body)),"Idempotency-Key is required");
        assertEquals("400 REQUEST_INVALID",outcome(place(consumer,"no-consent-1",body.replace("\"acceptedPreInformation\":true","\"acceptedPreInformation\":false"))));
        assertEquals("404 PRODUCT_NOT_FOUND",outcome(place(consumer,"discovery-1",order(s,line(discoveryOnly,1,"20.00")))),"no retail price, not for sale");
        assertEquals("404 PRODUCT_NOT_FOUND",outcome(place(consumer,"private-01",order(s,line(privateOne,1,"30.00")))),"not public, not for sale");
        assertEquals("409 PRICE_CHANGED",outcome(place(consumer,"b2b-price-1",order(s,line(retail,1,"50.00")))),"the B2B price is not the consumer price");

        var created=place(consumer,"retail-0001",body);
        assertEquals(201,created.statusCode(),created.body());
        var o=json.readTree(created.body()).get("orders").get(0);
        assertEquals("B2C",o.get("channel").asString()); assertTrue(o.get("buyerCompanyId").isNull());
        assertEquals("239.80",o.get("totalAmount").asString()); assertEquals("BANK_TRANSFER",o.get("paymentMethod").asString());
        assertTrue(o.get("lines").get(0).get("taxIncluded").asBoolean()); assertEquals("Ayşe Yılmaz",o.get("buyerName").asString());
        assertEquals("draft-1",jdbc.queryForObject("SELECT terms_version FROM orders WHERE id=? AND terms_accepted_at IS NOT NULL",String.class,o.get("id").asString()));
        assertEquals(200,place(consumer,"retail-0001",body).statusCode(),"same key and body replays");
        assertEquals("409 IDEMPOTENCY_KEY_REUSED",outcome(place(consumer,"retail-0001",body.replace("Kadıköy","Üsküdar"))));

        String id=o.get("id").asString();
        var stranger=user(); UUID other=company(stranger);
        assertEquals("404 ORDER_NOT_FOUND",outcome(send(stranger,"GET","/api/v1/me/orders/"+id,null)),"another person cannot read it");
        assertEquals("404 ORDER_NOT_FOUND",outcome(send(stranger,"GET","/api/v1/companies/"+other+"/sales/"+id,null)));
        var sale=json.readTree(send(seller,"GET","/api/v1/companies/"+s+"/sales/"+id,null).body()).get("order");
        assertEquals("İstanbul",sale.get("shippingAddress").get("city").asString(),"the seller needs the address to ship");
        assertEquals(1,json.readTree(send(consumer,"GET","/api/v1/me/orders",null).body()).get("totalElements").asInt());

        jdbc.update("UPDATE companies SET suspended_at=UTC_TIMESTAMP(6) WHERE id=?",s.toString());
        assertEquals("404 PRODUCT_NOT_FOUND",outcome(place(consumer,"suspended-1",order(s,line(retail,1,"119.90")))));
        jdbc.update("UPDATE companies SET suspended_at=NULL WHERE id=?",s.toString());
    }

    @Test void withdrawalReturnsAndRefunds() throws Exception {
        var seller=user(); UUID s=company(seller);
        String p=product(seller,s,"WD-1","Lamba","100.00","250.00",true);
        assertEquals(200,send(seller,"PUT","/api/v1/companies/"+s+"/products/"+p+"/stock","{\"quantity\":5}").statusCode());
        var consumer=user();
        String sales="/api/v1/companies/"+s+"/sales/", mine="/api/v1/me/orders/";

        String a=placed(consumer,p,2); assertEquals(3,stock(p));
        act(seller,sales+a+"/accept","{\"expectedVersion\":0}");
        assertEquals("CANCELLED",act(consumer,mine+a+"/cancel","{\"expectedVersion\":1}").get("status").asString(),"withdrawal before shipping");
        assertEquals(5,stock(p));

        String b=placed(consumer,p,1);
        act(seller,sales+b+"/accept","{\"expectedVersion\":0}");
        assertEquals("409 ORDER_STATE_CONFLICT",outcome(send(seller,"POST",sales+b+"/payment","{\"expectedVersion\":1,\"status\":\"REFUNDED\"}")));
        act(seller,sales+b+"/payment","{\"expectedVersion\":1,\"status\":\"PAID\"}");
        act(seller,sales+b+"/ship","{\"expectedVersion\":2,\"carrier\":\"Aras\",\"trackingNumber\":\"A-1\"}");
        assertEquals("409 ORDER_STATE_CONFLICT",outcome(send(consumer,"POST",mine+b+"/cancel","{\"expectedVersion\":3}")),"after shipping only a return");
        var delivered=act(consumer,mine+b+"/confirm-delivery","{\"expectedVersion\":3}");
        assertFalse(delivered.get("deliveredAt").isNull());
        assertEquals(400,send(consumer,"POST",mine+b+"/return-request","{\"expectedVersion\":4}").statusCode(),"a return needs a reason");
        assertEquals("RETURN_REQUESTED",act(consumer,mine+b+"/return-request","{\"expectedVersion\":4,\"note\":\"Beğenmedim\"}").get("status").asString());
        assertEquals(4,stock(p),"stock comes back only with the goods");
        assertEquals("RETURNED",act(seller,sales+b+"/confirm-return","{\"expectedVersion\":5}").get("status").asString());
        assertEquals(5,stock(p));
        assertEquals("REFUNDED",act(seller,sales+b+"/payment","{\"expectedVersion\":6,\"status\":\"REFUNDED\"}").get("paymentStatus").asString());

        String c=placed(consumer,p,1);
        act(seller,sales+c+"/accept","{\"expectedVersion\":0}");
        act(seller,sales+c+"/ship","{\"expectedVersion\":1}");
        act(seller,sales+c+"/deliver","{\"expectedVersion\":2}");
        jdbc.update("UPDATE orders SET delivered_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 DAY) WHERE id=?",c);
        assertEquals("409 RETURN_WINDOW_CLOSED",outcome(send(consumer,"POST",mine+c+"/return-request","{\"expectedVersion\":3,\"note\":\"Geç kaldım\"}")));
        var history=new ArrayList<String>();
        json.readTree(send(consumer,"GET",mine+b,null).body()).get("events").forEach(e->history.add(e.get("action").asString()));
        assertEquals(List.of("PLACED","ACCEPTED","PAYMENT_RECORDED","SHIPPED","DELIVERY_CONFIRMED","RETURN_REQUESTED","RETURN_CONFIRMED","PAYMENT_REFUNDED"),history);
    }

    /** A-06: validation problems name the field and the rule, never the submitted value. */
    @Test void validationProblemsNameTheFields() throws Exception {
        var consumer=user(); UUID seller=UUID.randomUUID();
        String body=order(seller,line(UUID.randomUUID().toString(),1,"1.00"))
            .replace("\"+90 555 111 22 33\"","\"secret-not-a-phone\"").replace("\"acceptedPreInformation\":true","\"acceptedPreInformation\":false");
        var invalid=place(consumer,"field-errors-1",body);
        assertEquals("400 REQUEST_INVALID",outcome(invalid));
        var errors=new HashSet<String>();
        json.readTree(invalid.body()).get("errors").forEach(e->errors.add(e.get("field").asString()+"/"+e.get("code").asString()));
        assertEquals(Set.of("shippingAddress.phone/Pattern","acceptedPreInformation/AssertTrue"),errors);
        assertFalse(invalid.body().contains("secret-not-a-phone"),"the submitted value is never echoed");
        var unknown=json.readTree(place(consumer,"field-errors-2",body.replace("{\"orders\"","{\"channel\":\"B2B\",\"orders\"")).body());
        assertEquals("channel",unknown.get("errors").get(0).get("field").asString());
        assertEquals("UNKNOWN_FIELD",unknown.get("errors").get(0).get("code").asString());
        var wrongType=json.readTree(place(consumer,"field-errors-3",order(seller,line(UUID.randomUUID().toString(),1,"1.00")).replace("\"quantity\":1","\"quantity\":\"bir\"")).body());
        assertEquals("orders[0].lines[0].quantity",wrongType.get("errors").get(0).get("field").asString());
        assertEquals("INVALID_FORMAT",wrongType.get("errors").get(0).get("code").asString());
    }

    String placed(User consumer,String p,int quantity) throws Exception {
        UUID seller=UUID.fromString(jdbc.queryForObject("SELECT seller_company_id FROM catalog_products WHERE id=?",String.class,p));
        var response=place(consumer,UUID.randomUUID().toString(),order(seller,line(p,quantity,"250.00")));
        assertEquals(201,response.statusCode(),response.body());
        return json.readTree(response.body()).get("orders").get(0).get("id").asString();
    }
    JsonNode act(User user,String path,String body) throws Exception {
        var response=send(user,"POST",path,body);
        assertEquals(200,response.statusCode(),path+" -> "+response.body());
        return json.readTree(response.body()).get("order");
    }
    static String line(String product,int quantity,String price) { return "{\"productId\":\""+product+"\",\"quantity\":"+quantity+",\"expectedUnitPrice\":\""+price+"\"}"; }
    static String order(UUID seller,String line) {
        return "{\"orders\":[{\"sellerCompanyId\":\""+seller+"\",\"lines\":["+line+"]}],\"shippingAddress\":"+ADDRESS
            +",\"paymentMethod\":\"BANK_TRANSFER\",\"acceptedPreInformation\":true,\"acceptedDistanceSalesContract\":true}";
    }
    HttpResponse<String> place(User consumer,String key,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/orders"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf(consumer));
        if(key!=null) builder.header("Idempotency-Key",key);
        return consumer.http().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Creates, sets the retail price (null: none), publishes and optionally makes public. */
    String product(User seller,UUID s,String sku,String name,String b2b,String retail,boolean makePublic) throws Exception {
        String c="/api/v1/companies/"+s+"/products";
        var created=send(seller,"POST",c,"{\"sku\":\""+sku+"\",\"name\":\""+name+"\",\"price\":{\"amount\":\""+b2b+"\",\"currency\":\"TRY\",\"taxIncluded\":false}"
            +(retail==null?"":",\"retailPrice\":{\"amount\":\""+retail+"\",\"currency\":\"TRY\"}")+"}");
        assertEquals(201,created.statusCode(),created.body());
        var p=json.readTree(created.body()); String id=p.get("id").asString();
        long v=json.readTree(send(seller,"POST",c+"/"+id+"/publish","{\"expectedVersion\":"+p.get("version").asLong()+"}").body()).get("version").asLong();
        if(makePublic) assertEquals(200,send(seller,"PUT",c+"/"+id+"/public-visibility","{\"expectedVersion\":"+v+",\"publicVisible\":true}").statusCode());
        return id;
    }
    int stock(String p) { return jdbc.queryForObject("SELECT stock_quantity FROM catalog_products WHERE id=?",Integer.class,p); }
    User user() throws Exception {
        var created=identity.register(new RegisterRequest("m7-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M7"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",created.id().toString());
        var user=new User(created.id(),HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build());
        String form="email="+URLEncoder.encode(created.email(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8);
        assertEquals(204,request(user,"POST","/api/v1/auth/login",form,"application/x-www-form-urlencoded").statusCode());
        return user;
    }
    UUID company(User owner) throws Exception {
        return UUID.fromString(json.readTree(send(owner,"POST","/api/v1/companies","{\"name\":\"M7 "+UUID.randomUUID()+"\"}").body()).get("id").asString());
    }
    String outcome(HttpResponse<String> response) {
        if(response.body()==null || response.body().isBlank()) return Integer.toString(response.statusCode());
        var code=json.readTree(response.body()).get("code");
        return code==null ? Integer.toString(response.statusCode()) : response.statusCode()+" "+code.asString();
    }
    HttpResponse<String> send(User user,String method,String path,String body) throws Exception { return request(user,method,path,body,"application/json"); }
    String csrf(User user) throws Exception { return json.readTree(request(user,"GET","/api/v1/auth/csrf",null,null).body()).get("token").asString(); }
    HttpResponse<String> request(User user,String method,String path,String body,String type) throws Exception {
        var client=user==null?HttpClient.newHttpClient():user.http();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(type!=null) builder.header("Content-Type",type);
        if(user!=null && !method.equals("GET")) builder.header("X-CSRF-TOKEN",csrf(user));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String tempDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix).toString(); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required for real MySQL");
        return value;
    }
}
