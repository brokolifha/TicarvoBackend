# Ticarvo Backend — M1 + M2 + M3 + M4

Java 25, Spring Boot 4.1.1, MySQL/Flyway ve JDBC cookie oturumları. M1 hesap/firma temeli; M2 satıcı keşfi, paylaşım kodu ve yönlü B2B başvuru/onay/ret/iptal akışıdır. M3 özel katalog, B2B liste fiyatı ve fiyatsız açık keşif ekler. Sipariş, ödeme, stok, çalışan yönetimi ve frontend kapsam dışındadır.

## Çalıştırma

Java 25 ve ayrı bir MySQL veritabanı gerekir. Native MySQL 8.0.40 ile doğrulanır; Windows geliştirmede Docker zorunlu değildir. Maven wrapper 3.9.16 projededir. İlk bağımlılık indirmesinde internet gerekir.

PowerShell ortamına kendi boş geliştirme veritabanınızın DB_URL, DB_USERNAME, DB_PASSWORD bilgilerini koyup yerel HTTP için SPRING_PROFILES_ACTIVE=local seçin; ardından `./mvnw.cmd spring-boot:run` çalıştırın. DB_URL örneği: `jdbc:mysql://127.0.0.1:3306/ticarvo_dev?connectionTimeZone=UTC`. Uygulama varsayılan veritabanına bağlanmaz; gerekli bağlantı eksikse açılmaz. .env.example kendiliğinden yüklenmez.

Flyway V1–V5 sürümlerini uygular, Hibernate yalnız validate yapar. Kullanılmış migration dosyalarını değiştirmeyin. Open-in-view kapalıdır. local profili yalnız HTTP localhost için Secure cookie bayrağını kapatır. Üretimde local kullanılmaz: TICARVO_SESSION cookie Secure, HttpOnly, SameSite=Lax, Path=/ ve host-only'dir.

## API sözleşmesi ve akış

`docs/openapi.json` OpenAPI 3.1 sözleşmesidir; frontend tipleri bu dosyadan üretilebilir (örneğin `openapi-typescript`). Hatalar application/problem+json, tarihler UTC ISO-8601, kimlikler UUID. Yanıtlar no-store; özel veri DTO'larla sınırlanır.

1. GET /api/v1/auth/csrf ile maskeli token alın; cookie'yi koruyun.
2. POST /auth/register JSON email/password/displayName: 201, otomatik giriş yok. Parola en az12 karakter, en fazla72 UTF-8 byte; BCrypt12.
3. POST /auth/login form-urlencoded email/password: 204. CSRF gerekli. Başarıdan sonra CSRF yeniden alın; GET /auth/me kullanıcıyı verir.
4. POST /companies JSON name: firma ve server-assigned OWNER. Liste ve detay yalnız üyelikle erişilir.
5. POST /auth/logout CSRF ile204; session iptal edilir.

Yukarıdaki kısaltılmış yollar /api/v1 altındadır. Bütün unsafe istekler, resolve dahil, cookie/CSRF ister. JSON ve application/*+json gövdeleri POST/PUT/PATCH/DELETE dahil16 KiB ile sınırlıdır. Client actor/role/status atayamaz; DTO'da olmayan JSON alanı400 verir.

## M2 satıcı keşfi

Yeni ve eski firmalar başlangıçta gizlidir. OWNER, PUT /companies/{id}/seller-profile gövdesinde listed true/false ile açık liste seçimini yapar. GET /sellers ve /sellers/{id} anonimdir; yalnız opted-in id/name gösterir. Listede yer almak doğrulanmış satıcı rozeti değildir. Özel /companies erişimi bu seçimle açılmaz.

OWNER, POST /companies/{id}/trade-invitation ile192 bit rastgele, yedi gün geçerli, çok kullanımlı kod oluşturur/yeniler. Kod yalnız bu yanıtta bir kez gösterilir; DB'de SHA-256 hash tutulur. DELETE aynı yol kodu iptal eder. Kod API query/path/log'larına yazılmaz. Gelecek frontend manuel kod veya fragment tabanlı paylaşım kullanabilir; şu anda çalışan paylaşım sayfası yoktur.

POST /trade-invitations/resolve gövdesi {code}: oturumlu kullanıcıya minimum satıcı özeti. Resolve kalıcı izin bileti değildir; apply sırasında kod tekrar kontrol edilir. Yanlış/expired/revoked kod404 INVITATION_NOT_FOUND.

## M2 ilişkiler

Buyer OWNER, POST /companies/{buyerId}/trade-relationships ile tam birini gönderir:
- {sellerCompanyId}: yalnız public satıcıya başvuru.
- {invitationCode}: güncel kodla gizli/açık satıcıya başvuru.

Başarı201 PENDING ilişki ve Location. Kendine başvuru400; aynı yönlü çiftin ikinci başvurusu409 RELATIONSHIP_EXISTS. A→B ile B→A ayrı ilişkilerdir.

OWNER kendi firma bağlamında GET relationship liste/detay kullanır. Liste direction=INCOMING veya OUTGOING ister; status opsiyoneldir. page0..100000, size1..100, varsayılan20. DTO: id, buyerCompanyId, sellerCompanyId, status, version, createdAt, updatedAt.

Satıcı OWNER POST /companies/{sellerId}/trade-relationships/{id}/approve|reject|revoke gövdesinde {expectedVersion} yollar. PENDING→APPROVED/REJECTED; APPROVED→REVOKED. REJECTED/REVOKED terminal; M2 yeniden başvuru/restore/silme sağlamaz. Stale version409 RELATIONSHIP_VERSION_CONFLICT, yanlış geçiş409 RELATIONSHIP_STATE_CONFLICT. Alıcı tarafın karar girişimi403; görünmez/olmayan nesne404. Önce yetki, sonra state/version kontrol edilir.

İlişki ve append-only audit aynı transaction'dadır. Bütün ilgili yazmalar hedef seller company satırını kilitler; kilit sonrası invitation/relationship current-read ile doğrulanır. Buyer satırı ayrıca kilitlenmez. @Version ek yarış korumasıdır. Lock timeout/deadlock409 CONCURRENT_OPERATION verir; istemci yeniden okuyarak güvenli tekrar kararı vermelidir. Otomatik yan etkili retry yoktur.

Public opt-out veya kod iptali önceki ilişkileri değiştirmez. İlişki revoke ayrı işlemdir. APPROVED, M3 katalog erişiminin yalnız bir önkoşuludur; ürün grant ve PUBLISHED durumu da gerekir. İç servis predicate'i cache kullanmaz; M3 katalog girişinde buyer yetkisi ayrıca doğrulanır.

## Kotalar ve yayın sınırları

Kimliği doğrulanmış kullanıcı için bütün firmalarda ortak15dk kotaları: apply30, resolve60, rotate10, company-create10. İşlem türleri ayrıdır. En fazla10.000 anahtar, TTL ve doluyken fail-closed. ticarvo.limits.* yapılandırması window-seconds, capacity, apply, resolve, rotate, company-create alanlarını alır. 429 Retry-After içerir. Ham kod/buyer ID principal kotasını değiştirmez.

M1 IP login/register limiti proxy arkasında tüm kullanıcıları ortak kotaya sokabilir; SEC-M1-002 yayın blokajı devam eder. Sayaçlar tek process belleğindedir, restartta sıfırlanır ve çok instance için ortak değildir. Çoklu sahte hesap, public/session uçlarının edge koruması, e-posta sahipliği, satıcı doğrulama ve moderasyon genel yayın öncesi açık işlerdir.

CORS varsayılan same-origin. Gerekirse CORS_ALLOWED_ORIGINS açık HTTPS/localhost origin listesi; wildcard yok. İzinli yöntemler GET/POST/PUT/DELETE/OPTIONS. Forwarded IP başlıklarına doğrudan güvenilmez.

## Testler

TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD zorunludur; H2 veya sessiz skip yok. Yalnız sentetik, ayrı yerel MySQL kullanın. Tam test paketi için ticarvo_m2_test ve ticarvo_m2_upgrade_test adlı iki şemaya yetkili test kullanıcısı gerekir. URL örneği `jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test?connectionTimeZone=UTC`. Mevcut kullanıcı veritabanlarını kullanmayın.

`./mvnw.cmd test` M1 membership, M2 servis/HTTP, gerçek bağımsız transaction yarışları, audit rollback, token expiry, kota ve migration testlerini çalıştırır. Upgrade testi yalnız loopback+beklenen schema ismini kabul eder; yalnız ticarvo_m2_upgrade_test şemasındaki sentetik verileri temizleyip V1/V2→V3 geçişini, boş şema→V4 ve V3→V4 geçişinde eski kullanıcı/firma/üyelik/ilişki/davet kayıtlarının korunmasını doğrular; migration tekrarı sıfırdır. Ana test şemasında UUID'li sentetik kayıtlar birikir; testler global sabit satır sayısına dayanmaz.

Çalışan local test JAR'ında `./scripts/http-smoke.ps1 -BaseUrl http://127.0.0.1:8080 -MailOutbox <uygulamanın giden kutusu klasörü>` M1 gerçek cookie/CSRF regresyonudur; kullanıcıları giden kutusundaki kodla doğrular. Başarısız check nonzero hata üretir; sentetik sonuç dosyaları Git dışıdır. Bu script test veritabanı dışında çalıştırılmaz.

## Dağıtım

Backend Dockerfile çok aşamalı ve root olmayan Java25 runtime hedefler. İş akışı: test edilmiş imajı benzersiz tag/digest ile build→registry push→VPS pull. Ortak Compose koordinasyon outputs/deployment altındadır. Bu kaynak teslimi gerçek Docker runtime, TLS/tarayıcı cookie bütünleşmesi, image taraması, yedek geri yükleme veya canlı deploy kanıtı değildir. Genel yayın mimari/güvenlik blokajları kapatıldıktan sonra ayrı doğrulanır.

## Paket ve yapılandırma düzeni

Her iş modülünde controller, service, repository, entity ve dto paketleri ayrıdır; durum enumları model altında bulunur. Entity → DTO dönüşümü service/mapper sorumluluğundadır. Modüller yalnız CurrentUserProvider, CompanyAccess ve TradeAccess arayüzleri üzerinden birbirine erişir; repository başka modüle açılmaz. ArchUnit testleri bu sınırları bytecode üzerinden denetler.

Uygulama ayarları src/main/resources/application.properties ve yalnız açık local profil için application-local.properties dosyalarındadır. Veritabanı bilgileri zorunlu ortam değişkenlerinden gelir; local profil varsayılan değildir. ConfigurationPropertiesTest, güvenli cookie varsayılanı dahil mevcut ayarları doğrular. Katalog controller/service/entity/repository/dto katmanları ve V4 bu düzene uyar.
## M3 katalog

Satıcı OWNER /companies/{sellerId}/products altında oluşturur/listeleyebilir; detay PUT yalnız DRAFT içerik/fiyat düzenler. /publish, /unpublish, /archive POST; /public-visibility ve /buyers PUT, bütün değişimlerde expectedVersion ister. ARCHIVED terminaldir. SKU trim + büyük harfe dönüşür, satıcı içinde arşiv dahil benzersizdir. Satıcı başına arşiv dahil1000 ürün sınırı vardır.

Fiyat {amount:"125.50",currency:"TRY",taxIncluded:false}; amount JSON string, en fazla17 tam/2 kesir hanesi. TRY/USD/EUR kabul edilir; negatif, exponent, ek ondalık ve sessiz yuvarlama yoktur. Sıfır geçerli; vergi hesabı, alıcıya özel fiyat ve satın alma yoktur.

Alıcı yolu /companies/{buyerId}/catalog/sellers/{sellerId}/products: güncel buyer OWNER + buyer→seller APPROVED + ürün grant + PUBLISHED birlikte zorunlu. İlişki yoksa404, onay var ama grant yoksa boş liste. /buyers tam kümeyi değiştirir, en fazla100 ayrı onaylı buyer; uygunsuz tek öğe tümünü geri alır. Gerçek değişim sürüm ve atomik audit üretir.

Anonim /catalog/products yalnız PUBLISHED ve publicVisible=true ürünleri gösterir. Açıkça public yapmak, firma dizininde gizli olsa bile satıcı adını/ID'sini gösterir. Public DTO yalnız id/sellerCompanyId/sellerName/name/description; oturum eklemek fiyat/SKU açmaz. Public varsayılanfalse ve ayrı sürümlü endpoint'ten açılır. İçerik düz metindir; frontend ham HTML render etmemelidir.

Katalog giriş servisleri REQUIRES_NEW + READ_COMMITTED işleminde çalışır. Private akış seller kilidi sonrası OWNER'ı tekrar okur; iptal commit'inden sonra kilit alan okuma eski veri vermez. Önceden alınmış cevabı geri çekme garantisi yoktur. Bu girişler gelecekte sipariş transaction'ından çağrılmamalıdır: bağımsız commit eder ve caller seller kilidi tutuyorsa bekleyebilir. Public liste seller kilitlemez; RC count/content eşzamanlı değişimde farklı anları yansıtabilir. No-store ve ayrı DTO gizliliği korunur.

Principal başına bütün firmalarda ortak15dk kotaları: product-create30, product-mutate120, catalog-read300; public katalog IP başına120. ticarvo.limits.product-create/product-mutate/catalog-read ayarlanabilir. Bounded TTL kullanılır. Public katalog IP anahtarları login/kayıt anahtarlarından ayrı bir limiter'da (her biri en fazla 10.000 aktif anahtar) tutulur. Public kapasite dolarsa yalnız yeni public okumalar 429 alır; login/kayıt etkilenmez (SEC-M3-001). Public ürün yayından kaldırılıp tekrar yayımlanırsa publicVisible seçimi korunur. Proxy ortak-IP ve tek-instance/restart sınırları devam eder; yerel teslim yayın onayı değildir.

## M4.1 hesap yaşam döngüsü

Kayıttan sonra hesaba bir doğrulama kodu gönderilir. Login, e-posta doğrulanana kadar **parola doğruysa** 403 `EMAIL_NOT_VERIFIED` döner; yanlış parola her durumda 401 döner. M4.1 öncesinde oluşturulmuş hesaplar V5 migration'ı ile doğrulanmış sayılır.

| Uç | Açıklama |
|---|---|
| `POST /api/v1/auth/email-verification` `{token}` | 204; bilinmeyen, süresi geçmiş veya kullanılmış kod için aynı `400 TOKEN_INVALID` |
| `POST /api/v1/auth/email-verification/resend` `{email}` | Her durumda 202 |
| `POST /api/v1/auth/password-reset/request` `{email}` | Her durumda 202; kod 30 dakika geçerli |
| `POST /api/v1/auth/password-reset` `{token,newPassword}` | 204; e-postayı doğrulanmış sayar ve hesabın **bütün oturumlarını** kapatır |
| `POST /api/v1/auth/sessions/revoke-all` | Oturum gerekli; mevcut oturum dahil hepsini kapatır |

**Kodlar ve sınırlar:**
- Kodlar 32 bayt rastgele değerdir ve tek kullanımlıktır; veritabanında yalnız SHA-256 hash'leri tutulur.
- Aynı hesap için 60 saniyede en fazla bir posta gönderilir; yeni kod eskisini geçersiz kılar.
- IP başına 15 dakikalık kotalar: yeniden gönderme 5, sıfırlama isteği 5, doğrulama ve sıfırlama onayı 20.

**Posta (K-19):** Her e-posta, `ticarvo.mail.outbox-dir` klasörüne bir `.txt` dosyası olarak yazılır. Local profilde bu klasör `MAIL_OUTBOX_DIR` ortam değişkeninden alınır; tanımlı değilse `target/mail-outbox` kullanılır. **Klasör tanımlı değilse uygulama açılmaz.** Bu bir geliştirme aracıdır; VPS'e çıkmadan önce gerçek bir posta sağlayıcısı seçilmelidir (yayın blokajı REL-M4-MAIL). Ayrıntılar: ADR-004.

## M4.2 iki adımlı giriş (TOTP)

İsteğe bağlıdır ve authenticator uygulamalarıyla çalışır (RFC 6238: SHA-1, 6 hane, 30 saniye). Ayrıntılar: ADR-005.

| Uç | Açıklama |
|---|---|
| `POST /api/v1/auth/mfa/setup` | `{secret, otpauthUri}` döner; URI QR koda çevrilebilir. MFA zaten açıksa 409 |
| `POST /api/v1/auth/mfa/enable` `{code}` | 10 kurtarma kodu döner; **yalnız bir kez gösterilir** |
| `POST /api/v1/auth/mfa/disable` `{code}` | TOTP veya kurtarma kodu ile kapatır; sır ve kurtarma kodları silinir |
| `POST /api/v1/auth/login` | MFA açıksa forma `code` alanı eklenir. Kod eksik veya yanlışsa, parola doğruysa 401 `MFA_REQUIRED` |

**Güvenlik davranışı:**
- Aynı kod iki kez kullanılamaz.
- Hesap başına 15 dakikada en fazla 5 kod denemesi yapılabilir; aşılınca 429.
- Parola sıfırlama MFA'yı kapatmaz.
- `/auth/me` yanıtında `mfaEnabled` alanı var.

**Anahtar:** Sır, AES-256-GCM ile şifrelenip saklanır. Anahtar `ticarvo.mfa.encryption-key` ayarından gelir (base64, 32 bayt); **anahtar yoksa veya geçersizse uygulama açılmaz.** Local profil, `MFA_ENCRYPTION_KEY` ortam değişkeni verilmemişse depodaki geliştirme anahtarını kullanır. Üretimde bu anahtar kullanılmaz; anahtar değişince mevcut sırlar okunamaz hale gelir (REL-M4-MFAKEY).

## M4.3 firma rolleri ve ekip

Her firmada tam olarak bir **OWNER** vardır; bu kural veritabanında da zorunludur. Bunun yanında **ADMIN** ve **MEMBER** rolleri bulunur. Ayrıntılar ve piyasa araştırması: ADR-006.

| İşlem | Yetkili roller |
|---|---|
| Katalog yönetimi, alıcı katalogunu görme, üye listesi, ilişkiler ve profili görme | Bütün üyeler |
| Bayi başvurusu, başvuruya onay/ret/iptal, satıcı profili, paylaşım kodu | ADMIN ve OWNER |
| MEMBER davet etme ve çıkarma | ADMIN ve OWNER |
| ADMIN davet etme ve çıkarma, rol değiştirme, sahiplik devri | Yalnız OWNER |

**Davet:**
- `POST /companies/{id}/member-invitations` `{email, role}` ile oluşturulur; kod e-postaya gider.
- Davet edilen kişi kendi hesabıyla `POST /member-invitations/accept` `{token}` çağırır. Kod yalnız davet edilen e-postanın hesabında çalışır, tek kullanımlıktır ve 7 gün geçerlidir.
- Bir firmada üyeler ve bekleyen davetler toplamı en fazla 50 olabilir.

**Sahiplik devri:** `POST /companies/{id}/ownership-transfer` `{userId}` ile yapılır; hedef mevcut bir ADMIN olmalıdır. Eski OWNER, ADMIN olur. OWNER firmadan ayrılmadan önce sahipliği devretmelidir.

**Hata kodları:** Yetki eksikse 403 `OWNER_REQUIRED` veya `ADMIN_REQUIRED`. Üye olunmayan firmada her zamanki gibi 404.

## M4.4 katalog keşfi: kategori, arama, görsel

Ayrıntılar ve araştırma: ADR-007.

**Kategori:** Platformun sabit iki seviyeli ağacı vardır (8 ana, 32 alt kategori; `V8` ile gelir). `GET /catalog/categories` anonimdir. Ürüne yalnız alt kategori atanabilir (`categoryId`, boş bırakılabilir). Listelerde `categoryId` filtresi ana kategori verilirse alt kategorilerini de kapsar; bilinmeyen id 400 döner.

**Arama:** Üç listede de `q` parametresi var (2–100 karakter, içerir araması; `%` ve `_` düz metin sayılır).
- Public katalog: yalnız ürün adı.
- Satıcı ve alıcı listesi: ad veya SKU.

**Görsel:**
- Yükleme: `POST /companies/{sellerId}/products/{productId}/images` (multipart: `file`, `expectedVersion`). MEMBER ve üstü, yalnız DRAFT üründe.
- Sınırlar: ürün başına en fazla 5 görsel, dosya başına 5 MB, kenar başına 4000 px. Yalnız JPEG ve PNG; tür dosya imzasından anlaşılır, SVG/GIF/WebP reddedilir (`IMAGE_INVALID`).
- Görsel sunucuda yeniden kodlanır; EXIF ve konum gibi gömülü bilgiler silinir. Dosya adı sunucu tarafından üretilir.
- Sıra: `PUT .../images/order`, ilk görsel kapaktır. Silme: `DELETE .../images/{imageId}?expectedVersion=`.
- Okuma, ürünü kim görebiliyorsa ona açıktır: satıcı üyeleri, ürünü görebilen alıcı (CATALOG_READ kotasından düşer) ve public ürünlerde anonim ziyaretçi (IP başına 15 dakikada 600, liste kotasından ayrı).
- Yanıtlar `nosniff` ve `no-store` başlıklarıyla döner.

**Depolama:** `ImageStore` arayüzü; şimdilik yerel disk (`ticarvo.storage.image-dir`, local profilde `IMAGE_DIR`, varsayılan `target/images`). Ayar yoksa uygulama açılmaz. S3 geçişi ve yedekleme yayın öncesi yapılacak (REL-M4-IMAGES). WebP desteği M4.4b'ye bırakıldı.

## M4A platform admini, şikâyet ve askıya alma

Ayrıntılar ve araştırma (AB DSA Madde 16, 17 ve 20): ADR-008.

**Admin atama:** Yalnız komut satırından yapılır; HTTP üzerinden bir yolu yoktur. Komut, çalışan uygulamayla aynı `DB_*` ayarlarıyla çalıştırılır, web portunu açmaz ve işini bitirince kapanır.
```
java -jar ticarvo-backend.jar --ticarvo.admin.grant=admin@ornek.com
java -jar ticarvo-backend.jar --ticarvo.admin.revoke=admin@ornek.com
```
- Atanacak hesabın e-postası doğrulanmış ve iki adımlı girişi (MFA) açık olmalı.
- Admin API'si (`/api/v1/admin/...`) her istekte üç şeyi kontrol eder: admin listesi, açık MFA ve oturumun MFA koduyla açılmış olması. Bunlardan biri eksikse 403 `PLATFORM_ADMIN_REQUIRED` veya `MFA_REQUIRED` döner.
- Yetki geri alınınca açık oturumlar da yetkiyi hemen kaybeder. Platform admini firma uç noktalarında OWNER yetkisi almaz.

**Şikâyet:** `POST /complaints` `{companyId, productId?, reason, description}` ile açılır.
- Kullanıcı yalnız kendi şikâyetlerini görür (`GET /complaints`, `GET /complaints/{id}`). Şikâyet edilen taraf şikâyeti ve şikâyetçiyi hiç görmez.
- Kullanıcı başına 15 dakikada 10 şikâyet ve en fazla 20 açık şikâyet açılabilir; aynı hedefe ikinci açık şikâyet açılamaz.
- Admin şikâyeti önce `review`, sonra `resolve` ile kapatır (`ACTION_TAKEN` veya `DISMISSED`, iç not zorunlu). Şikâyet hiçbir zaman kendiliğinden bir kısıtlama doğurmaz.

**Askıya alma:** `POST /admin/users/{id}/suspension` ve `POST /admin/companies/{id}/suspension` `{reason, statement, complaintId?}` ile yapılır, `.../restore` ile geri alınır.
- **Kullanıcı:** Bütün oturumları kapanır. Doğru parola ve kodla giriş yapmaya çalışırsa 403 `ACCOUNT_SUSPENDED` alır; yanlış parolada hata, normal hesaplardakiyle aynıdır.
- **Firma:** Public dizinden, public katalogdan ve alıcı kataloglarından kaybolur; yeni başvuru alamaz. Üyeleri firma işlemlerinde 403 `COMPANY_SUSPENDED` alır, ama `GET /companies/{id}` yanıtındaki `suspended` alanını görebilir. Veri silinmez.
- **Bildirim:** Gerekçe e-postayla gönderilir: kullanıcıya veya firmanın OWNER ve ADMIN'lerine. E-postada tek kullanımlık bir itiraz kodu vardır.

**İtiraz:** Girişsiz yapılır: `POST /moderation/appeals` `{token, text}`.
- Her karar için bir kez itiraz edilebilir ve kod 180 gün geçerlidir.
- Admin `POST /admin/appeals/{id}/decide` ile karar verir. `REVERSED` kararı kısıtlamayı kaldırır.

**Audit:** Her admin değişikliği, aynı işlem içinde `admin_audit_log` tablosuna yazılır. Kararlar ekleme yapılan, değiştirilemeyen `moderation_restrictions` tablosunda tutulur.

## M5–M6 B2B sipariş ve işletim

Ayrıntılar ve araştırma (Shopify Wholesale, Faire, Mercur, Ankorstore): ADR-009.

**Model:** Satıcı onaylı sipariş. Alıcı, kendisine görünür ürünleri liste fiyatından sipariş eder; satıcı kabul ya da reddeder. Teklif (RFQ) yoktur.

**Checkout:** `POST /companies/{buyerId}/orders` ile yapılır ve `Idempotency-Key` başlığı zorunludur.
```json
{"orders":[{"sellerCompanyId":"…","note":"…","lines":[{"productId":"…","quantity":4,"expectedUnitPrice":"12.50"}]}]}
```
- Bir istek birden fazla satıcıyı içerebilir. Her satıcı için ayrı bir sipariş oluşur ve hepsi aynı `checkoutId` altında toplanır. İşlem ya tamamen başarılı olur ya hiç olmaz.
- **Tekrar koruması:** Aynı anahtar ve aynı içerikle gelen tekrar 200 ile ilk sonucu döndürür. Aynı anahtar farklı bir içerikle kullanılırsa 409 `IDEMPOTENCY_KEY_REUSED` döner.
- **Fiyat ve görünürlük kontrolleri:**
  - Fiyat değişmişse 409 `PRICE_CHANGED` döner; istemci ürünleri yeniden okumalıdır.
  - Ürün görünür değilse 404 `PRODUCT_NOT_FOUND` döner. Sebep ne olursa olsun (izin yok, ilişki yok, ürün yayında değil, satıcı askıda) cevap aynıdır.
  - Satırlar farklı para birimindeyse 400 `MIXED_CURRENCY` döner.
- **Değişmez satır:** Satırlar (SKU, ad, birim fiyat, vergi bayrağı, miktar, tutar) sipariş anında kopyalanır; katalog sonradan değişse de sipariş değişmez. Vergi ve indirim hesaplanmaz.

**Stok:** İsteğe bağlıdır: `PUT /companies/{sellerId}/products/{id}/stock` `{quantity}` ile ayarlanır; `null` takibi kapatır.
- Takip edilen üründe stok sipariş anında ayrılır. Yetersiz stokta 409 `INSUFFICIENT_STOCK` döner; aynı anda gelen siparişler aşırı satış üretmez.
- Ret ve iptalde stok geri eklenir.
- Alıcı kalan stoğu `availableQuantity` alanında görür.

**Durumlar:** `PLACED` → `ACCEPTED` → `SHIPPED` → `DELIVERED`. Yan dallar `REJECTED` ve `CANCELLED`.
- **Alıcı** (`/companies/{buyerId}/orders/{id}/…`): satıcı kabul etmeden `cancel`; sipariş gönderildikten sonra `confirm-delivery`.
- **Satıcı** (`/companies/{sellerId}/sales/{id}/…`): `accept`, `reject` (gerekçe zorunlu), `cancel` (kabulden sonra, gerekçe zorunlu), `ship` (kargo firması ve takip numarası isteğe bağlı), `deliver`, `payment` (`PAID` veya `UNPAID`).
- Her işlem `expectedVersion` ister. Her geçiş sipariş geçmişine (`events`) yazılır.

**Ödeme ve kargo:** Ödeme platform dışında yapılır (havale, vade, çek); Ticarvo yalnız durumunu tutar. Kargo entegrasyonu yoktur.

**Yetki:** İki firmanın bütün üyeleri siparişi görebilir ve işletebilir. Taraf olmayan firma 404 alır.

## M7 B2C satın alma

Ayrıntılar, araştırma ve hukuki notlar (Mesafeli Sözleşmeler Yönetmeliği, Fiyat Etiketi Yönetmeliği, 6493 sayılı Kanun): ADR-010.

**Kim alabilir:** Giriş yapmış her kullanıcı, firması olmasa da bireysel olarak alışveriş yapabilir. İlk sürümde misafir alım yoktur. Bireysel siparişler kişiye aittir; firma üyeleri bunları görmez.

**Fiyat:** Satıcı ürüne B2B fiyatından ayrı, isteğe bağlı bir `retailPrice` `{amount, currency}` girer.
- Perakende fiyatı her zaman tüm vergiler dahildir ve B2B fiyatı gibi yalnız DRAFT durumunda değişir.
- Public katalog `retailPrice` gösterir; fiyat girilmemişse `null` döner ve ürün yalnız keşifte görünür. B2B fiyatı ve SKU public tarafta hâlâ görünmez.
- Satın alınabilir ürün: public, `PUBLISHED`, perakende fiyatı olan ve satıcısı askıda olmayan ürün.

**Sipariş:** `POST /me/orders` ile verilir; `Idempotency-Key` başlığı zorunludur.
```json
{"orders":[{"sellerCompanyId":"…","lines":[{"productId":"…","quantity":1,"expectedUnitPrice":"119.90"}]}],
 "shippingAddress":{"recipient":"…","phone":"+90 …","address":"…","district":"…","city":"…","postalCode":"34710"},
 "paymentMethod":"BANK_TRANSFER","acceptedPreInformation":true,"acceptedDistanceSalesContract":true}
```
- Tekrar koruması, fiyat kontrolü, stok ayırma ve satıcı başına ayrı sipariş B2B'deki gibi çalışır.
- **Onaylar:** Ön bilgilendirme ve sözleşme onayının ikisi de `true` olmalıdır. Onay zamanı ve metin sürümü (`ticarvo.legal.terms-version`) siparişe yazılır.
- **Adres:** Siparişe kopyalanır. Yalnız tüketici ve o siparişin satıcısı görür.
- **Ödeme:** `BANK_TRANSFER` veya `CASH_ON_DELIVERY`; para platformdan geçmez. Kartla ödeme, lisanslı bir ödeme kuruluşuyla sözleşme yapılınca eklenecek (REL-M7-PAYMENT).

**Cayma ve iade:**
- Tüketici sipariş gönderilmeden `POST /me/orders/{id}/cancel` ile iptal edebilir (`PLACED` veya `ACCEPTED`).
- Sipariş yoldayken ya da teslimden sonraki 14 gün içinde `return-request` ile iade isteyebilir (gerekçe zorunlu). Süre dolmuşsa 409 `RETURN_WINDOW_CLOSED` döner.
- Satıcı ürün geri gelince `POST /companies/{sellerId}/sales/{id}/confirm-return` çağırır; stok geri eklenir. Para iadesini `payment` `{status:"REFUNDED"}` ile kaydeder; bu yalnız daha önce ödenmiş, iptal ya da iade edilmiş siparişte mümkündür.

**Satıcı:** `/sales` listesinde B2B ve B2C siparişler birlikte görünür (`channel` alanı ayırır). Kabul, ret, gönderim, teslim ve ödeme işlemleri iki kanalda da aynıdır.

## M8 bütünleşik kabul

Durum: yerel kabul verildi, koşullu (27 Ekim 2026). Ayrıntılar `md ler` klasöründe:
- `Ticarvo-Backend-M8-Inceleme.md`: güvenlik ve mimari incelemesi.
- `Ticarvo-Backend-Acik-Risk-Listesi.md`: yayın engelleri ve kabul edilmiş riskler.
- `Ticarvo-Backend-Frontend-Devir-Paketi.md`: oturum, CSRF ve hata sözleşmesi, rol matrisi, durum makineleri.

Uçtan uca referans akış `kanit/M8-2026-10-27/m8-e2e.ps1` betiğindedir.
