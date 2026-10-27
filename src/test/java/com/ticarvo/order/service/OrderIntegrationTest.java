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
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-009 checkout, idempotency, price and access checks, stock and the order state machine over real HTTP and MySQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OrderIntegrationTest {
    static final String PASSWORD="Synthetic-M5-Password!95";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",()->tempDirectory("ticarvo-m5-outbox"));
        registry.add("ticarvo.storage.image-dir",()->tempDirectory("ticarvo-m5-images"));
    }
    @Autowired IdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    record User(UUID id,HttpClient http) {}
    record Seller(User user,UUID id) {}

    @Test void checkoutSplitsPerSellerIsIdempotentAndSnapshotsLines() throws Exception {
        var buyer=user(); UUID b=company(buyer);
        var s1=seller(); var s2=seller();
        approve(buyer,b,s1); approve(buyer,b,s2);
        String p1=product(s1,"S1-A","Kalem","12.50",b), p2=product(s1,"S1-B","Silgi","3.00",b), p3=product(s2,"S2-A","Defter","40.00",b);
        String body=checkout(group(s1.id(),"Kapıya teslim",line(p1,4,"12.50"),line(p2,10,"3")),group(s2.id(),null,line(p3,1,"40.00")));
        assertEquals("400 VALIDATION_FAILED",outcome(order(buyer,b,null,body)),"Idempotency-Key is required");
        var first=order(buyer,b,"checkout-0001",body);
        assertEquals(201,first.statusCode(),first.body());
        var result=json.readTree(first.body());
        assertEquals(2,result.get("orders").size(),"one order per seller");
        var o1=byseller(result,s1.id());
        assertEquals("80.00",o1.get("totalAmount").asString());
        assertEquals("PLACED",o1.get("status").asString()); assertEquals("UNPAID",o1.get("paymentStatus").asString());
        assertEquals("Kapıya teslim",o1.get("buyerNote").asString());
        assertEquals("50.00",o1.get("lines").get(0).get("lineAmount").asString());

        var replay=order(buyer,b,"checkout-0001",body);
        assertEquals(200,replay.statusCode(),"same key and body replays");
        assertEquals(result.get("checkoutId").asString(),json.readTree(replay.body()).get("checkoutId").asString());
        assertEquals("409 IDEMPOTENCY_KEY_REUSED",outcome(order(buyer,b,"checkout-0001",checkout(group(s1.id(),null,line(p1,5,"12.50"))))));
        assertEquals(2L,count("SELECT COUNT(*) FROM orders WHERE buyer_company_id=?",b));

        // The snapshot survives catalog changes.
        long v=version(s1,p1);
        v=json.readTree(send(s1.user(),"POST","/api/v1/companies/"+s1.id()+"/products/"+p1+"/unpublish","{\"expectedVersion\":"+v+"}").body()).get("version").asLong();
        assertEquals(200,send(s1.user(),"PUT","/api/v1/companies/"+s1.id()+"/products/"+p1,"{\"expectedVersion\":"+v+",\"sku\":\"S1-A\",\"name\":\"Yeni Kalem\","
            +"\"price\":{\"amount\":\"15.00\",\"currency\":\"TRY\",\"taxIncluded\":false}}").statusCode());
        v=version(s1,p1);
        assertEquals(200,send(s1.user(),"POST","/api/v1/companies/"+s1.id()+"/products/"+p1+"/publish","{\"expectedVersion\":"+v+"}").statusCode());
        var stored=json.readTree(send(buyer,"GET","/api/v1/companies/"+b+"/orders/"+o1.get("id").asString(),null).body()).get("order").get("lines").get(0);
        assertEquals("Kalem",stored.get("name").asString()); assertEquals("12.50",stored.get("unitAmount").asString());

        // A changed price, an ungranted product and a self-order all fail without leaving anything behind.
        assertEquals("409 PRICE_CHANGED",outcome(order(buyer,b,"checkout-0002",checkout(group(s1.id(),null,line(p1,1,"12.50"))))));
        String hidden=product(s2,"S2-H","Gizli","1.00");
        assertEquals("404 PRODUCT_NOT_FOUND",outcome(order(buyer,b,"checkout-0003",checkout(group(s1.id(),null,line(p1,1,"15.00")),group(s2.id(),null,line(hidden,1,"1.00"))))));
        assertEquals("400 VALIDATION_FAILED",outcome(order(buyer,b,"checkout-0004",checkout(group(b,null,line(p1,1,"15.00"))))));
        assertEquals("400 REQUEST_INVALID",outcome(order(buyer,b,"checkout-0005",checkout(group(s1.id(),null,line(p1,0,"15.00"))))));
        assertEquals(2L,count("SELECT COUNT(*) FROM orders WHERE buyer_company_id=?",b),"failed checkouts write nothing");
        assertEquals(1L,count("SELECT COUNT(*) FROM order_checkouts WHERE buyer_company_id=?",b));

        // Only the two parties see an order.
        var outsider=user(); UUID x=company(outsider);
        assertEquals("404 ORDER_NOT_FOUND",outcome(send(outsider,"GET","/api/v1/companies/"+x+"/orders/"+o1.get("id").asString(),null)));
        assertEquals("404 ORDER_NOT_FOUND",outcome(send(s2.user(),"GET","/api/v1/companies/"+s2.id()+"/sales/"+o1.get("id").asString(),null)));
        assertEquals(1,json.readTree(send(s1.user(),"GET","/api/v1/companies/"+s1.id()+"/sales",null).body()).get("totalElements").asInt());
    }

    @Test void stateMachineStockAndHistory() throws Exception {
        var buyer=user(); UUID b=company(buyer); var s=seller();
        approve(buyer,b,s); String p=product(s,"STK-1","Koli","5.00",b);
        long before=version(s,p);
        var stocked=json.readTree(send(s.user(),"PUT","/api/v1/companies/"+s.id()+"/products/"+p+"/stock","{\"quantity\":5}").body());
        assertEquals(5,stocked.get("stockQuantity").asInt()); assertEquals(before,stocked.get("version").asLong(),"stock is not content");
        assertEquals(5,json.readTree(send(buyer,"GET","/api/v1/companies/"+b+"/catalog/sellers/"+s.id()+"/products/"+p,null).body()).get("availableQuantity").asInt());

        var a=place(buyer,b,s,p,3); assertEquals(2,stock(p));
        assertEquals("409 INSUFFICIENT_STOCK",outcome(order(buyer,b,UUID.randomUUID().toString(),checkout(group(s.id(),null,line(p,3,"5.00"))))));
        assertEquals("CANCELLED",act(buyer,"/api/v1/companies/"+b+"/orders/"+a.id+"/cancel",a.version,null).get("status").asString());
        assertEquals(5,stock(p),"cancel gives stock back");

        var o=place(buyer,b,s,p,2);
        String sale="/api/v1/companies/"+s.id()+"/sales/"+o.id;
        assertEquals("409 ORDER_STATE_CONFLICT",outcome(send(s.user(),"POST",sale+"/ship","{\"expectedVersion\":0}")),"must be accepted first");
        assertEquals("409 ORDER_STATE_CONFLICT",outcome(send(s.user(),"POST",sale+"/payment","{\"expectedVersion\":0,\"status\":\"PAID\"}")));
        var accepted=act(s.user(),sale+"/accept",0,null);
        assertEquals("409 ORDER_VERSION_CONFLICT",outcome(send(s.user(),"POST",sale+"/accept","{\"expectedVersion\":0}")));
        assertEquals("409 ORDER_STATE_CONFLICT",outcome(send(buyer,"POST","/api/v1/companies/"+b+"/orders/"+o.id+"/cancel","{\"expectedVersion\":1}")),"buyer cannot cancel after acceptance");
        assertEquals("404 ORDER_NOT_FOUND",outcome(send(buyer,"POST","/api/v1/companies/"+b+"/sales/"+o.id+"/accept","{\"expectedVersion\":1}")),"the buyer cannot act as seller");
        var shipped=json.readTree(send(s.user(),"POST",sale+"/ship","{\"expectedVersion\":"+accepted.get("version").asLong()+",\"carrier\":\"Yurtiçi\",\"trackingNumber\":\"TRK-123\"}").body()).get("order");
        assertEquals("SHIPPED",shipped.get("status").asString()); assertEquals("TRK-123",shipped.get("trackingNumber").asString());
        var paid=act(s.user(),sale+"/payment",shipped.get("version").asLong(),"{\"expectedVersion\":"+shipped.get("version").asLong()+",\"status\":\"PAID\"}");
        assertEquals("PAID",paid.get("paymentStatus").asString());
        var delivered=act(buyer,"/api/v1/companies/"+b+"/orders/"+o.id+"/confirm-delivery",paid.get("version").asLong(),null);
        assertEquals("DELIVERED",delivered.get("status").asString());
        var events=json.readTree(send(s.user(),"GET",sale,null).body()).get("events");
        var actions=new ArrayList<String>(); events.forEach(e->actions.add(e.get("action").asString()));
        assertEquals(List.of("PLACED","ACCEPTED","SHIPPED","PAYMENT_RECORDED","DELIVERY_CONFIRMED"),actions);
        assertEquals(3,stock(p),"a delivered order keeps its stock");

        var r=place(buyer,b,s,p,1);
        assertEquals(400,send(s.user(),"POST","/api/v1/companies/"+s.id()+"/sales/"+r.id+"/reject","{\"expectedVersion\":0}").statusCode(),"reject needs a reason");
        assertEquals("REJECTED",act(s.user(),"/api/v1/companies/"+s.id()+"/sales/"+r.id+"/reject",0,"{\"expectedVersion\":0,\"note\":\"Stokta yok\"}").get("status").asString());
        assertEquals(3,stock(p),"reject gives stock back");
    }

    @Test void concurrentOrdersNeverOversellAndReplaysCreateOneCheckout() throws Exception {
        var s=seller(); var b1=user(); UUID c1=company(b1); var b2=user(); UUID c2=company(b2);
        approve(b1,c1,s); approve(b2,c2,s); String p=product(s,"RACE-1","Son Ürün","9.00",c1,c2);
        assertEquals(200,send(s.user(),"PUT","/api/v1/companies/"+s.id()+"/products/"+p+"/stock","{\"quantity\":1}").statusCode());
        String body=checkout(group(s.id(),null,line(p,1,"9.00")));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            Future<Integer> f1=pool.submit(()->{ start.await(); return order(b1,c1,"race-buyer-1",body).statusCode(); });
            Future<Integer> f2=pool.submit(()->{ start.await(); return order(b2,c2,"race-buyer-2",body).statusCode(); });
            start.countDown();
            var codes=new ArrayList<>(List.of(f1.get(30,TimeUnit.SECONDS),f2.get(30,TimeUnit.SECONDS))); Collections.sort(codes);
            assertEquals(List.of(201,409),codes,"exactly one buyer gets the last unit");
            assertEquals(0,stock(p));

            assertEquals(200,send(s.user(),"PUT","/api/v1/companies/"+s.id()+"/products/"+p+"/stock","{\"quantity\":10}").statusCode());
            var again=new CountDownLatch(1);
            Future<Integer> r1=pool.submit(()->{ again.await(); return order(b1,c1,"same-key-123",body).statusCode(); });
            Future<Integer> r2=pool.submit(()->{ again.await(); return order(b1,c1,"same-key-123",body).statusCode(); });
            again.countDown();
            var replays=new ArrayList<>(List.of(r1.get(30,TimeUnit.SECONDS),r2.get(30,TimeUnit.SECONDS))); Collections.sort(replays);
            assertEquals(List.of(200,201),replays,"a parallel retry returns the first result");
            assertEquals(1L,count("SELECT COUNT(*) FROM order_checkouts WHERE buyer_company_id=? AND idempotency_key='same-key-123'",c1));
            assertEquals(9,stock(p),"the retry reserved nothing");
        } finally { pool.shutdownNow(); }
    }

    record Placed(String id,long version) {}
    Placed place(User buyer,UUID b,Seller s,String p,int quantity) throws Exception {
        var response=order(buyer,b,UUID.randomUUID().toString(),checkout(group(s.id(),null,line(p,quantity,"5.00"))));
        assertEquals(201,response.statusCode(),response.body());
        var o=json.readTree(response.body()).get("orders").get(0);
        return new Placed(o.get("id").asString(),o.get("version").asLong());
    }
    JsonNode act(User user,String path,long version,String body) throws Exception {
        var response=send(user,"POST",path,body==null?"{\"expectedVersion\":"+version+"}":body);
        assertEquals(200,response.statusCode(),path+" -> "+response.body());
        return json.readTree(response.body()).get("order");
    }
    JsonNode byseller(JsonNode result,UUID seller) {
        for(var o:result.get("orders")) if(o.get("sellerCompanyId").asString().equals(seller.toString())) return o;
        throw new AssertionError("no order for "+seller);
    }
    static String line(String product,int quantity,String price) { return "{\"productId\":\""+product+"\",\"quantity\":"+quantity+",\"expectedUnitPrice\":\""+price+"\"}"; }
    static String group(UUID seller,String note,String... lines) {
        return "{\"sellerCompanyId\":\""+seller+"\""+(note==null?"":",\"note\":\""+note+"\"")+",\"lines\":["+String.join(",",lines)+"]}";
    }
    static String checkout(String... groups) { return "{\"orders\":["+String.join(",",groups)+"]}"; }
    HttpResponse<String> order(User buyer,UUID b,String key,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/companies/"+b+"/orders"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf(buyer));
        if(key!=null) builder.header("Idempotency-Key",key);
        return buyer.http().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    Seller seller() throws Exception {
        var user=user(); UUID id=company(user);
        assertEquals(200,send(user,"PUT","/api/v1/companies/"+id+"/seller-profile","{\"listed\":true}").statusCode());
        return new Seller(user,id);
    }
    void approve(User buyer,UUID b,Seller s) throws Exception {
        String relation=json.readTree(send(buyer,"POST","/api/v1/companies/"+b+"/trade-relationships","{\"sellerCompanyId\":\""+s.id()+"\"}").body()).get("id").asString();
        assertEquals(200,send(s.user(),"POST","/api/v1/companies/"+s.id()+"/trade-relationships/"+relation+"/approve","{\"expectedVersion\":0}").statusCode());
    }
    /** Creates, grants to the given buyers (none: private) and publishes. */
    String product(Seller s,String sku,String name,String price,UUID... buyers) throws Exception {
        String c="/api/v1/companies/"+s.id()+"/products";
        var p=json.readTree(send(s.user(),"POST",c,"{\"sku\":\""+sku+"\",\"name\":\""+name+"\",\"price\":{\"amount\":\""+price+"\",\"currency\":\"TRY\",\"taxIncluded\":false}}").body());
        String id=p.get("id").asString(); long v=p.get("version").asLong();
        var granted=Arrays.stream(buyers).map(x->"\""+x+"\"").toList();
        if(!granted.isEmpty()) v=json.readTree(send(s.user(),"PUT",c+"/"+id+"/buyers","{\"expectedVersion\":"+v+",\"buyerCompanyIds\":["+String.join(",",granted)+"]}").body()).get("productVersion").asLong();
        assertEquals(200,send(s.user(),"POST",c+"/"+id+"/publish","{\"expectedVersion\":"+v+"}").statusCode());
        return id;
    }
    long version(Seller s,String p) throws Exception { return json.readTree(send(s.user(),"GET","/api/v1/companies/"+s.id()+"/products/"+p,null).body()).get("version").asLong(); }
    int stock(String p) { return jdbc.queryForObject("SELECT stock_quantity FROM catalog_products WHERE id=?",Integer.class,p); }
    long count(String sql,UUID id) { return jdbc.queryForObject(sql,Long.class,id.toString()); }
    User user() throws Exception {
        var created=identity.register(new RegisterRequest("m5-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M5"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",created.id().toString());
        var user=new User(created.id(),HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build());
        String form="email="+URLEncoder.encode(created.email(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8);
        assertEquals(204,request(user,"POST","/api/v1/auth/login",form,"application/x-www-form-urlencoded").statusCode());
        return user;
    }
    UUID company(User owner) throws Exception {
        return UUID.fromString(json.readTree(send(owner,"POST","/api/v1/companies","{\"name\":\"M5 "+UUID.randomUUID()+"\"}").body()).get("id").asString());
    }
    String outcome(HttpResponse<String> response) {
        if(response.body()==null || response.body().isBlank()) return Integer.toString(response.statusCode());
        var code=json.readTree(response.body()).get("code");
        return code==null ? Integer.toString(response.statusCode()) : response.statusCode()+" "+code.asString();
    }
    HttpResponse<String> send(User user,String method,String path,String body) throws Exception { return request(user,method,path,body,"application/json"); }
    String csrf(User user) throws Exception { return json.readTree(request(user,"GET","/api/v1/auth/csrf",null,null).body()).get("token").asString(); }
    HttpResponse<String> request(User user,String method,String path,String body,String type) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if(type!=null) builder.header("Content-Type",type);
        if(!method.equals("GET")) builder.header("X-CSRF-TOKEN",csrf(user));
        return user.http().send(builder.build(),HttpResponse.BodyHandlers.ofString());
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
