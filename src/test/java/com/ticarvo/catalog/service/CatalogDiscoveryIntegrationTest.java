package com.ticarvo.catalog.service;
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
import java.io.ByteArrayOutputStream;
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

/** ADR-007 categories, search and images over real HTTP, MySQL and the local image store. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class CatalogDiscoveryIntegrationTest {
    static final Path IMAGES=tempDirectory("ticarvo-images");
    static final String PASSWORD="Synthetic-M44-Password!95";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->required("TEST_DB_URL"));
        registry.add("spring.datasource.username",()->required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->required("TEST_DB_PASSWORD"));
        registry.add("ticarvo.mail.outbox-dir",()->tempDirectory("ticarvo-m44-outbox").toString());
        registry.add("ticarvo.storage.image-dir",IMAGES::toString);
    }
    @Autowired IdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    record User(UUID id,HttpClient http) {}
    record Market(User seller,UUID s,User buyer,UUID b,User outsider,UUID o) {}

    @Test void categoryTreeAndAssignmentRules() throws Exception {
        var tree=json.readTree(request(null,"GET","/api/v1/catalog/categories",null,null).body());
        assertEquals(8,tree.size());
        for(JsonNode top:tree) assertEquals(4,top.get("children").size(),top.get("code").asString());
        var m=market(); String c="/api/v1/companies/"+m.s()+"/products";
        assertEquals("400 VALIDATION_FAILED",outcome(send(m.seller(),"POST",c,product("TOP-1","Top level",category("ofis-kirtasiye")))),"top-level not assignable");
        assertEquals("400 VALIDATION_FAILED",outcome(send(m.seller(),"POST",c,product("UNKNOWN-1","Unknown",UUID.randomUUID()))));
        var created=json.readTree(send(m.seller(),"POST",c,product("LEAF-1","Leaf",category("kirtasiye"))).body());
        assertEquals(category("kirtasiye").toString(),created.get("categoryId").asString());
        var none=json.readTree(send(m.seller(),"POST",c,product("NONE-1","No category",null)).body());
        assertTrue(none.get("categoryId").isNull(),"category is optional");
    }

    @Test void searchRespectsEachAudienceAndTreatsWildcardsLiterally() throws Exception {
        var m=market(); String c="/api/v1/companies/"+m.s()+"/products";
        String red=publish(m,product("RED-PEN","Kırmızı Kalem",category("kirtasiye")),true,true);
        publish(m,product("BLUE-PEN","Mavi Kalem",category("kirtasiye")),true,false);
        publish(m,product("TAPE_50","Koli Bandı 50%",category("etiket-bant")),true,true);
        publish(m,product("SECRET-1","Gizli Ürün",category("kirtasiye")),false,false);
        // Seller panel: name or SKU, all statuses.
        assertEquals(2,total(m.seller(),c+"?q=kalem"));
        assertEquals(2,total(m.seller(),c+"?q=KALEM"),"case-insensitive");
        assertEquals(1,total(m.seller(),c+"?q=RED"),"SKU search");
        assertEquals(1,total(m.seller(),c+"?q=50%25"),"'%' matched literally");
        assertEquals(0,total(m.seller(),c+"?q=_%25"),"'_%' matched literally, not as a wildcard");
        assertEquals(3,total(m.seller(),c+"?categoryId="+category("ofis-kirtasiye")),"top-level filter covers subcategories");
        assertEquals(1,total(m.seller(),c+"?categoryId="+category("etiket-bant")));
        assertEquals("400 VALIDATION_FAILED",outcome(request(m.seller(),"GET",c+"?q=a",null,null)));
        assertEquals("400 VALIDATION_FAILED",outcome(request(m.seller(),"GET",c+"?q="+"x".repeat(101),null,null)));
        assertEquals("400 VALIDATION_FAILED",outcome(request(m.seller(),"GET",c+"?categoryId="+UUID.randomUUID(),null,null)));
        // Buyer catalog: only granted products.
        String b="/api/v1/companies/"+m.b()+"/catalog/sellers/"+m.s()+"/products";
        assertEquals(2,total(m.buyer(),b+"?q=kalem"));
        assertEquals(0,total(m.buyer(),b+"?q=Gizli"),"ungranted product is not searchable");
        assertEquals(0,total(m.buyer(),b+"?q=SECRET"));
        assertEquals(1,total(m.buyer(),b+"?q=BLUE"),"buyers may search by SKU");
        // Public: public products only, and never by SKU.
        assertEquals(1,total(null,"/api/v1/catalog/products?sellerCompanyId="+m.s()+"&q=kalem"));
        assertEquals(0,total(null,"/api/v1/catalog/products?sellerCompanyId="+m.s()+"&q=RED-PEN"),"SKU is not public");
        assertEquals(1,total(null,"/api/v1/catalog/products?sellerCompanyId="+m.s()+"&categoryId="+category("ambalaj")));
        assertEquals(red,json.readTree(request(null,"GET","/api/v1/catalog/products?sellerCompanyId="+m.s()+"&q=Kırmızı",null,null).body())
            .get("content").get(0).get("id").asString());
    }

    @Test void imagesAreSanitizedOrderedLimitedAndAsPrivateAsTheProduct() throws Exception {
        var m=market(); String c="/api/v1/companies/"+m.s()+"/products";
        var p=json.readTree(send(m.seller(),"POST",c,product("IMG-1","Görselli",category("kirtasiye"))).body());
        String id=p.get("id").asString(); long v=p.get("version").asLong();
        var up=upload(m.seller(),c+"/"+id+"/images",ProductImagesTest.withApp1(ProductImagesTest.encode("jpg",40,30),"Exif\0\0GPS-SECRET"),v);
        assertEquals(200,up.statusCode()); var jpg=json.readTree(up.body()); v=jpg.get("version").asLong();
        String first=jpg.get("imageIds").get(0).asString();
        var second=json.readTree(upload(m.seller(),c+"/"+id+"/images",ProductImagesTest.encode("png",20,20),v).body());
        v=second.get("version").asLong(); String png=second.get("imageIds").get(1).asString();
        assertEquals("400 IMAGE_INVALID",outcome(upload(m.seller(),c+"/"+id+"/images","<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8),v)));
        assertEquals("409 PRODUCT_VERSION_CONFLICT",outcome(upload(m.seller(),c+"/"+id+"/images",ProductImagesTest.encode("png",5,5),v-1)));
        assertEquals("413 REQUEST_INVALID",outcome(upload(m.seller(),c+"/"+id+"/images",new byte[5*1024*1024+10],v)),"file over 5 MB");
        assertEquals("413 PAYLOAD_TOO_LARGE",outcome(upload(m.seller(),c+"/"+id+"/images",new byte[7*1024*1024],v)),"request over 6 MB");
        var reordered=json.readTree(send(m.seller(),"PUT",c+"/"+id+"/images/order","{\"expectedVersion\":"+v+",\"imageIds\":[\""+png+"\",\""+first+"\"]}").body());
        assertEquals(png,reordered.get("imageIds").get(0).asString(),"first image is the cover"); v=reordered.get("version").asLong();
        assertEquals("400 INVALID_IMAGE_ORDER",outcome(send(m.seller(),"PUT",c+"/"+id+"/images/order","{\"expectedVersion\":"+v+",\"imageIds\":[\""+png+"\",\""+png+"\"]}")));
        for(int i=0;i<3;i++) v=json.readTree(upload(m.seller(),c+"/"+id+"/images",ProductImagesTest.encode("png",3,3),v).body()).get("version").asLong();
        assertEquals("409 IMAGE_LIMIT_REACHED",outcome(upload(m.seller(),c+"/"+id+"/images",ProductImagesTest.encode("png",3,3),v)));
        var stored=bytes(m.seller(),c+"/"+id+"/images/"+first);
        assertEquals(200,stored.statusCode()); assertEquals("image/jpeg",stored.headers().firstValue("Content-Type").orElse(""));
        assertTrue(stored.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertFalse(new String(stored.body(),StandardCharsets.ISO_8859_1).contains("GPS-SECRET"),"metadata stripped before storage");
        try(var files=Files.walk(IMAGES)) {
            var productFiles=files.filter(Files::isRegularFile).filter(f->f.toString().contains(id)).toList();
            assertEquals(5,productFiles.size());
            assertTrue(productFiles.stream().allMatch(f->f.getParent().equals(IMAGES.resolve(m.s().toString()).resolve(id))),"seller/product folder only");
        }
        // Visibility: same rules as the product.
        String b="/api/v1/companies/"+m.b()+"/catalog/sellers/"+m.s()+"/products/"+id+"/images/"+first;
        String pub="/api/v1/catalog/products/"+id+"/images/"+first;
        assertEquals(404,bytes(m.buyer(),b).statusCode(),"draft product image hidden from buyer");
        assertEquals(404,bytes(null,pub).statusCode(),"draft product image hidden from public");
        v=publishExisting(m,id,v,true,false);
        assertEquals(200,bytes(m.buyer(),b).statusCode(),"granted buyer sees it");
        assertEquals(404,bytes(m.outsider(),"/api/v1/companies/"+m.o()+"/catalog/sellers/"+m.s()+"/products/"+id+"/images/"+first).statusCode());
        assertEquals(404,bytes(null,pub).statusCode(),"not public yet");
        assertEquals("409 PRODUCT_STATE_CONFLICT",outcome(upload(m.seller(),c+"/"+id+"/images",ProductImagesTest.encode("png",3,3),v)),"published content is frozen");
        v=json.readTree(send(m.seller(),"PUT",c+"/"+id+"/public-visibility","{\"expectedVersion\":"+v+",\"publicVisible\":true}").body()).get("version").asLong();
        var publicImage=bytes(null,pub);
        assertEquals(200,publicImage.statusCode()); assertEquals(0xff,publicImage.body()[0]&0xff);
        assertEquals("404 IMAGE_NOT_FOUND",outcome(request(null,"GET","/api/v1/catalog/products/"+id+"/images/"+UUID.randomUUID(),null,null)));
        // Removal renumbers the rest and deletes the file after commit.
        v=json.readTree(send(m.seller(),"POST",c+"/"+id+"/unpublish","{\"expectedVersion\":"+v+"}").body()).get("version").asLong();
        long filesBefore=imageFiles();
        var removed=json.readTree(request(m.seller(),"DELETE",c+"/"+id+"/images/"+png+"?expectedVersion="+v,null,null).body());
        assertEquals(4,removed.get("imageIds").size());
        assertEquals(first,removed.get("imageIds").get(0).asString(),"next image became the cover");
        assertEquals(filesBefore-1,imageFiles());
        assertEquals(404,bytes(m.seller(),c+"/"+id+"/images/"+png).statusCode());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product_images WHERE product_id=? AND position NOT BETWEEN 0 AND 3",Long.class,id));
    }

    Market market() throws Exception {
        User seller=user(), buyer=user(), outsider=user();
        UUID s=company(seller), b=company(buyer), o=company(outsider);
        assertEquals(200,send(seller,"PUT","/api/v1/companies/"+s+"/seller-profile","{\"listed\":true}").statusCode());
        String relation=json.readTree(send(buyer,"POST","/api/v1/companies/"+b+"/trade-relationships","{\"sellerCompanyId\":\""+s+"\"}").body()).get("id").asString();
        assertEquals(200,send(seller,"POST","/api/v1/companies/"+s+"/trade-relationships/"+relation+"/approve","{\"expectedVersion\":0}").statusCode());
        return new Market(seller,s,buyer,b,outsider,o);
    }
    /** Creates, optionally grants to the buyer, publishes and optionally makes public; returns the id. */
    String publish(Market m,String body,boolean grant,boolean makePublic) throws Exception {
        var p=json.readTree(send(m.seller(),"POST","/api/v1/companies/"+m.s()+"/products",body).body());
        publishExisting(m,p.get("id").asString(),p.get("version").asLong(),grant,makePublic);
        return p.get("id").asString();
    }
    long publishExisting(Market m,String id,long v,boolean grant,boolean makePublic) throws Exception {
        String c="/api/v1/companies/"+m.s()+"/products/"+id;
        if(grant) v=json.readTree(send(m.seller(),"PUT",c+"/buyers","{\"expectedVersion\":"+v+",\"buyerCompanyIds\":[\""+m.b()+"\"]}").body()).get("productVersion").asLong();
        v=json.readTree(send(m.seller(),"POST",c+"/publish","{\"expectedVersion\":"+v+"}").body()).get("version").asLong();
        if(makePublic) v=json.readTree(send(m.seller(),"PUT",c+"/public-visibility","{\"expectedVersion\":"+v+",\"publicVisible\":true}").body()).get("version").asLong();
        return v;
    }
    static String product(String sku,String name,UUID category) {
        return "{\"sku\":\""+sku+"\",\"name\":\""+name+"\",\"price\":{\"amount\":\"10\",\"currency\":\"TRY\",\"taxIncluded\":false}"
            +(category==null?"":",\"categoryId\":\""+category+"\"")+"}";
    }
    UUID category(String code) { return UUID.fromString(jdbc.queryForObject("SELECT id FROM catalog_categories WHERE code=?",String.class,code)); }
    long total(User user,String path) throws Exception {
        var response=request(user,"GET",path,null,null);
        assertEquals(200,response.statusCode(),path+" -> "+response.body());
        return json.readTree(response.body()).get("totalElements").asLong();
    }
    long imageFiles() throws IOException { try(var files=Files.walk(IMAGES)) { return files.filter(Files::isRegularFile).count(); } }
    User user() throws Exception {
        var created=identity.register(new RegisterRequest("m44-"+UUID.randomUUID()+"@example.invalid",PASSWORD,"Synthetic M44"));
        jdbc.update("UPDATE app_users SET email_verified_at=UTC_TIMESTAMP(6) WHERE id=?",created.id().toString());
        var user=new User(created.id(),HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build());
        String form="email="+URLEncoder.encode(created.email(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8);
        assertEquals(204,request(user,"POST","/api/v1/auth/login",form,"application/x-www-form-urlencoded").statusCode());
        return user;
    }
    UUID company(User owner) throws Exception {
        return UUID.fromString(json.readTree(send(owner,"POST","/api/v1/companies","{\"name\":\"M44 "+UUID.randomUUID()+"\"}").body()).get("id").asString());
    }
    HttpResponse<String> upload(User user,String path,byte[] file,long expectedVersion) throws Exception {
        String boundary="ticarvo-"+UUID.randomUUID();
        var body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"expectedVersion\"\r\n\r\n"+expectedVersion+"\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(file);
        body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .header("Content-Type","multipart/form-data; boundary="+boundary).header("X-CSRF-TOKEN",csrf(user));
        return user.http().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<byte[]> bytes(User user,String path) throws Exception {
        var client=user==null?HttpClient.newHttpClient():user.http();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
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
    static Path tempDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required for real MySQL");
        return value;
    }
}
