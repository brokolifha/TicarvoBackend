package com.ticarvo.catalog.service;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.entity.*;
import com.ticarvo.catalog.model.ProductStatus;
import com.ticarvo.catalog.repository.*;
import com.ticarvo.catalog.service.mapper.ProductMapper;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.trade.service.TradeAccess;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.PrincipalRateLimit;
import com.ticarvo.shared.storage.ImageStore;
import com.ticarvo.shared.validation.PageValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import static com.ticarvo.shared.security.PrincipalRateLimit.Operation.*;

/** Independent RC transaction prevents an upstream RR snapshot from retaining revoked access. */
@Service
@Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW)
public class CatalogService {
    static final int MAX_IMAGES = 5;
    private final ProductRepository products;
    private final ProductGrantRepository grants;
    private final ProductEventRepository events;
    private final CategoryRepository categories;
    private final ProductImageRepository images;
    private final ImageStore store;
    private final CompanyAccess companies;
    private final TradeAccess trade;
    private final PrincipalRateLimit limits;
    private final Clock clock;
    public CatalogService(ProductRepository products,ProductGrantRepository grants,ProductEventRepository events,
            CategoryRepository categories,ProductImageRepository images,ImageStore store,
            CompanyAccess companies,TradeAccess trade,PrincipalRateLimit limits,Clock clock) {
        this.products=products; this.grants=grants; this.events=events; this.categories=categories;
        this.images=images; this.store=store; this.companies=companies;
        this.trade=trade; this.limits=limits; this.clock=clock;
    }

    /**
     * Membership check, quota, seller lock, then a post-lock re-check (ADR-003; any member since ADR-006).
     * Reads take the shared lock (A-02): they no longer queue behind each other, yet still wait for an
     * in-flight change such as a revocation, which is what M3-R03 relies on.
     */
    private UUID authorize(UUID company,UUID seller,Authentication auth,PrincipalRateLimit.Operation operation) {
        UUID actor=companies.requireMember(company,auth);
        limits.check(operation,actor);
        if(operation==CATALOG_READ) companies.lockSellerForRead(seller); else companies.lockSeller(seller);
        companies.requireMember(company,auth);
        return actor;
    }
    private Product product(UUID seller,UUID id) {
        return products.findByIdAndSellerCompanyId(id,seller).orElseThrow(CatalogService::notFound);
    }
    private static void version(Product p,long expected) {
        if(p.version()!=expected) {
            throw error(HttpStatus.CONFLICT,"PRODUCT_VERSION_CONFLICT","Product changed. Reload before retrying.");
        }
    }
    private static void mutable(Product p) {
        if(p.status()==ProductStatus.ARCHIVED) throw state();
    }
    /** Content (fields, price, category, images) changes only while DRAFT (ADR-003, ADR-007). */
    private static void draft(Product p) {
        if(p.status()!=ProductStatus.DRAFT) throw state();
    }
    private void audit(Product p,UUID actor,String action) {
        products.flush();
        events.saveAndFlush(new ProductEvent(p,actor,action,clock.instant()));
    }
    private void unique(UUID seller,String sku,UUID except) {
        boolean duplicate=except==null
            ? products.existsBySellerCompanyIdAndSku(seller,sku)
            : products.existsBySellerCompanyIdAndSkuAndIdNot(seller,sku,except);
        if(duplicate) throw error(HttpStatus.CONFLICT,"SKU_CONFLICT","SKU already exists in this seller catalog.");
    }
    private CatalogDtos.Owner owner(Product p) {
        return ProductMapper.owner(p,imageIds(images,List.of(p)).getOrDefault(p.id(),List.of()));
    }

    public CatalogDtos.Owner create(UUID seller,CatalogDtos.Create request,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_CREATE);
        if(products.countBySellerCompanyId(seller)>=1000) {
            throw error(HttpStatus.CONFLICT,"CATALOG_LIMIT_REACHED","Seller catalog limit reached.");
        }
        var value=values(request.sku(),request.name(),request.description(),request.price());
        UUID category=category(request.categoryId());
        unique(seller,value.sku(),null);
        var created=new Product(seller,value.sku(),value.name(),value.description(),
            value.amount(),value.currency(),value.tax(),clock.instant());
        created.categorize(category);
        var retail=retail(request.retailPrice());
        created.retail(retail==null?null:retail.amount(),retail==null?null:retail.currency());
        var p=products.saveAndFlush(created);
        audit(p,actor,"CREATED");
        return owner(p);
    }
    public CatalogDtos.Page<CatalogDtos.Owner> list(UUID seller,ProductStatus status,String q,UUID categoryId,
            int page,int size,Authentication auth) {
        authorize(seller,seller,auth,CATALOG_READ);
        var filter=filter(q,categoryId,categories);
        var result=products.ownerList(seller,status,filter.pattern(),filter.anyCategory(),filter.categories(),page(page,size));
        var imageIds=imageIds(images,result.getContent());
        return new CatalogDtos.Page<>(result.map(p->ProductMapper.owner(p,imageIds.getOrDefault(p.id(),List.of()))).getContent(),
            page,size,result.getTotalElements());
    }
    public CatalogDtos.Owner get(UUID seller,UUID id,Authentication auth) {
        authorize(seller,seller,auth,CATALOG_READ);
        return owner(product(seller,id));
    }
    public CatalogDtos.Owner edit(UUID seller,UUID id,CatalogDtos.Edit request,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,request.expectedVersion());
        draft(p);
        var value=values(request.sku(),request.name(),request.description(),request.price());
        UUID category=category(request.categoryId());
        unique(seller,value.sku(),id);
        p.edit(value.sku(),value.name(),value.description(),value.amount(),value.currency(),value.tax(),clock.instant());
        p.categorize(category);
        var retail=retail(request.retailPrice());
        p.retail(retail==null?null:retail.amount(),retail==null?null:retail.currency());
        audit(p,actor,"EDITED");
        return owner(p);
    }
    public CatalogDtos.Owner transition(UUID seller,UUID id,long expected,ProductStatus target,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,expected);
        boolean allowed=p.status()==ProductStatus.DRAFT&&(target==ProductStatus.PUBLISHED||target==ProductStatus.ARCHIVED)
            ||p.status()==ProductStatus.PUBLISHED&&(target==ProductStatus.DRAFT||target==ProductStatus.ARCHIVED);
        if(!allowed) throw state();
        p.status(target,clock.instant());
        audit(p,actor,target.name());
        return owner(p);
    }
    public CatalogDtos.Owner visibility(UUID seller,UUID id,CatalogDtos.Visibility request,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,request.expectedVersion());
        mutable(p);
        if(p.publicVisible()!=request.publicVisible()) {
            p.publicVisible(request.publicVisible(),clock.instant());
            audit(p,actor,"PUBLIC_VISIBILITY");
        }
        return owner(p);
    }
    /**
     * Stock is operational: it changes in any non-archived state and leaves the content version alone (ADR-009).
     * Product events are one per content version, so stock changes are not among them.
     */
    public CatalogDtos.Owner stock(UUID seller,UUID id,CatalogDtos.Stock request,Authentication auth) {
        authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        mutable(p);
        p.stock(request.quantity());
        products.flush();
        return owner(p);
    }
    public CatalogDtos.Buyers buyers(UUID seller,UUID id,Authentication auth) {
        authorize(seller,seller,auth,CATALOG_READ);
        var p=product(seller,id);
        return new CatalogDtos.Buyers(p.version(),grants.buyers(id));
    }
    /** Full-set replacement; any ineligible buyer rejects the whole batch. */
    public CatalogDtos.Buyers replaceBuyers(UUID seller,UUID id,CatalogDtos.BuyersInput request,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,request.expectedVersion());
        mutable(p);
        var ids=request.buyerCompanyIds();
        if(ids==null||ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)||new HashSet<>(ids).size()!=ids.size()) {
            throw invalidBuyers();
        }
        for(UUID buyer:ids) {
            if(buyer.equals(seller)||!trade.hasApprovedRelationship(buyer,seller)) throw invalidBuyers();
        }
        if(!new HashSet<>(grants.buyers(id)).equals(new HashSet<>(ids))) {
            grants.removeAll(id);
            grants.flush();
            for(UUID buyer:ids) grants.save(new ProductGrant(id,seller,buyer,clock.instant()));
            grants.flush();
            p.touch(clock.instant());
            audit(p,actor,"BUYERS_CHANGED");
        }
        return new CatalogDtos.Buyers(p.version(),grants.buyers(id));
    }

    // Images (ADR-007): appended at the end, position 0 is the cover, at most MAX_IMAGES, DRAFT only.
    public CatalogDtos.Owner addImage(UUID seller,UUID id,long expectedVersion,byte[] content,Authentication auth) {
        // Cheap membership gate first, so outsiders cannot make the server decode images.
        companies.requireMember(seller,auth);
        var processed=ProductImages.process(content);
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,expectedVersion);
        draft(p);
        int count=images.findByProductIdOrderByPositionAsc(id).size();
        if(count>=MAX_IMAGES) throw error(HttpStatus.CONFLICT,"IMAGE_LIMIT_REACHED","A product can have at most "+MAX_IMAGES+" images.");
        var image=images.saveAndFlush(new ProductImage(id,seller,count,processed.contentType(),processed.bytes().length,
            processed.width(),processed.height(),clock.instant()));
        store.put(image.storageKey(),processed.bytes());
        p.touch(clock.instant());
        audit(p,actor,"IMAGE_ADDED");
        return owner(p);
    }
    public CatalogDtos.Owner removeImage(UUID seller,UUID id,UUID imageId,long expectedVersion,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,expectedVersion);
        draft(p);
        var image=images.findByIdAndProductId(imageId,id).orElseThrow(CatalogService::imageNotFound);
        images.delete(image);
        images.flush();
        var rest=images.findByProductIdOrderByPositionAsc(id);
        for(int i=0;i<rest.size();i++) rest.get(i).moveTo(i);
        String key=image.storageKey();
        // Delete the file only once the row is gone for good; a failed commit leaves the image intact.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { store.delete(key); }
        });
        p.touch(clock.instant());
        audit(p,actor,"IMAGE_REMOVED");
        return owner(p);
    }
    public CatalogDtos.Owner reorderImages(UUID seller,UUID id,CatalogDtos.ImageOrder request,Authentication auth) {
        UUID actor=authorize(seller,seller,auth,PRODUCT_MUTATE);
        var p=product(seller,id);
        version(p,request.expectedVersion());
        draft(p);
        var current=images.findByProductIdOrderByPositionAsc(id);
        var byId=current.stream().collect(Collectors.toMap(ProductImage::id,image->image));
        var order=request.imageIds();
        if(order.size()!=current.size()||!byId.keySet().equals(new HashSet<>(order))) {
            throw error(HttpStatus.BAD_REQUEST,"INVALID_IMAGE_ORDER","List every image of the product exactly once.");
        }
        if(current.stream().map(ProductImage::id).toList().equals(order)) return owner(p);
        for(int i=0;i<order.size();i++) byId.get(order.get(i)).moveTo(i);
        p.touch(clock.instant());
        audit(p,actor,"IMAGES_REORDERED");
        return owner(p);
    }
    public CatalogDtos.ImageContent ownerImage(UUID seller,UUID id,UUID imageId,Authentication auth) {
        authorize(seller,seller,auth,CATALOG_READ);
        product(seller,id);
        return content(images,store,id,imageId);
    }

    /** Unknown and unapproved sellers must be indistinguishable to the buyer (M3-REVIEW-01). */
    private void authorizeBuyer(UUID buyer,UUID seller,Authentication auth) {
        try {
            authorize(buyer,seller,auth,CATALOG_READ);
        } catch(ApiException failure) {
            if("SELLER_NOT_FOUND".equals(failure.code())) throw notFound();
            throw failure;
        }
        if(buyer.equals(seller)||!trade.hasApprovedRelationship(buyer,seller)) throw notFound();
    }
    public CatalogDtos.Page<CatalogDtos.Buyer> buyerList(UUID buyer,UUID seller,String q,UUID categoryId,
            int page,int size,Authentication auth) {
        authorizeBuyer(buyer,seller,auth);
        var filter=filter(q,categoryId,categories);
        var result=products.buyerList(seller,buyer,filter.pattern(),filter.anyCategory(),filter.categories(),page(page,size));
        var imageIds=imageIds(images,result.getContent());
        return new CatalogDtos.Page<>(result.map(p->ProductMapper.buyer(p,imageIds.getOrDefault(p.id(),List.of()))).getContent(),
            page,size,result.getTotalElements());
    }
    public CatalogDtos.Buyer buyerGet(UUID buyer,UUID seller,UUID id,Authentication auth) {
        authorizeBuyer(buyer,seller,auth);
        var p=products.buyerDetail(id,seller,buyer).orElseThrow(CatalogService::notFound);
        return ProductMapper.buyer(p,imageIds(images,List.of(p)).getOrDefault(p.id(),List.of()));
    }
    /** Same visibility as buyerGet: private product images are as confidential as the price. */
    public CatalogDtos.ImageContent buyerImage(UUID buyer,UUID seller,UUID id,UUID imageId,Authentication auth) {
        authorizeBuyer(buyer,seller,auth);
        products.buyerDetail(id,seller,buyer).orElseThrow(CatalogService::notFound);
        return content(images,store,id,imageId);
    }

    static Pageable page(int page,int size) {
        PageValidation.validatePage(page,size);
        return PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("id")));
    }
    /** Search and category filter shared by the owner, buyer and public lists (ADR-007). */
    record Filter(String pattern,boolean anyCategory,Collection<UUID> categories) {}
    static Filter filter(String q,UUID categoryId,CategoryRepository categories) {
        String pattern=com.ticarvo.shared.validation.SearchText.likePattern(q);
        if(categoryId==null) return new Filter(pattern,true,List.of(UUID.randomUUID()));
        var category=categories.findById(categoryId)
            .orElseThrow(()->error(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Unknown category."));
        // A top-level category covers its subcategories, which are the only ones products can carry.
        Collection<UUID> scope=category.isLeaf() ? List.of(category.id())
            : categories.findByParentId(category.id()).stream().map(Category::id).toList();
        return new Filter(pattern,false,scope.isEmpty()?List.of(UUID.randomUUID()):scope);
    }
    static Map<UUID,List<UUID>> imageIds(ProductImageRepository images,Collection<Product> page) {
        if(page.isEmpty()) return Map.of();
        return images.findByProductIdInOrderByPositionAsc(page.stream().map(Product::id).toList()).stream()
            .collect(Collectors.groupingBy(ProductImage::productId,Collectors.mapping(ProductImage::id,Collectors.toList())));
    }
    static CatalogDtos.ImageContent content(ProductImageRepository images,ImageStore store,UUID productId,UUID imageId) {
        var image=images.findByIdAndProductId(imageId,productId).orElseThrow(CatalogService::imageNotFound);
        return new CatalogDtos.ImageContent(image.contentType(),store.get(image.storageKey()));
    }
    private UUID category(UUID categoryId) {
        if(categoryId==null) return null;
        return categories.findById(categoryId).filter(Category::isLeaf).map(Category::id)
            .orElseThrow(()->error(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Category must be an existing subcategory."));
    }
    private record Values(String sku,String name,String description,BigDecimal amount,String currency,boolean tax) {}
    private static Values values(String sku,String name,String description,CatalogDtos.PriceInput price) {
        if(sku==null||name==null||price==null) throw validation();
        sku=sku.strip().toUpperCase(Locale.ROOT);
        name=name.strip();
        description=description==null?"":description;
        if(!sku.matches("[A-Z0-9._-]{1,64}")||name.isEmpty()||name.length()>200||description.length()>2000) throw validation();
        if(price.amount()==null||!price.amount().isString()||!price.amount().asString().matches("[0-9]{1,17}(\\.[0-9]{1,2})?")
                ||price.currency()==null||!Set.of("TRY","USD","EUR").contains(price.currency())
                ||price.taxIncluded()==null||!price.taxIncluded().isBoolean()) {
            throw validation();
        }
        return new Values(sku,name,description,new BigDecimal(price.amount().asString()).setScale(2),
            price.currency(),price.taxIncluded().asBoolean());
    }
    private record Retail(BigDecimal amount,String currency) {}
    /** Same amount and currency rules as the B2B price; null means no consumer price. */
    private static Retail retail(CatalogDtos.RetailPriceInput price) {
        if(price==null) return null;
        if(price.amount()==null||!price.amount().isString()||!price.amount().asString().matches("[0-9]{1,17}(\\.[0-9]{1,2})?")
                ||!Set.of("TRY","USD","EUR").contains(price.currency())) {
            throw validation();
        }
        return new Retail(new BigDecimal(price.amount().asString()).setScale(2),price.currency());
    }
    private static ApiException validation() {
        return error(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Product fields or price are invalid.");
    }
    private static ApiException invalidBuyers() {
        return error(HttpStatus.BAD_REQUEST,"INVALID_BUYER_SELECTION","Buyer selection is invalid.");
    }
    private static ApiException state() {
        return error(HttpStatus.CONFLICT,"PRODUCT_STATE_CONFLICT","Product state does not allow this action.");
    }
    static ApiException notFound() {
        return error(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Product was not found.");
    }
    static ApiException imageNotFound() {
        return error(HttpStatus.NOT_FOUND,"IMAGE_NOT_FOUND","Image was not found.");
    }
    private static ApiException error(HttpStatus status,String code,String detail) {
        return new ApiException(status,code,detail);
    }
}
