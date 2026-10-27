package com.ticarvo.catalog.service;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.entity.Category;
import com.ticarvo.catalog.repository.CategoryRepository;
import com.ticarvo.catalog.repository.ProductImageRepository;
import com.ticarvo.catalog.repository.ProductRepository;
import com.ticarvo.catalog.service.mapper.ProductMapper;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.shared.storage.ImageStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Anonymous, price-free discovery; no seller locks (ADR-003 public section, ADR-007 search and images). */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW)
public class PublicCatalogService {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductImageRepository images;
    private final ImageStore store;
    private final CompanyAccess companies;
    public PublicCatalogService(ProductRepository products,CategoryRepository categories,ProductImageRepository images,
            ImageStore store,CompanyAccess companies) {
        this.products=products; this.categories=categories; this.images=images; this.store=store; this.companies=companies;
    }
    public CatalogDtos.Page<CatalogDtos.PublicProduct> list(UUID seller,String q,UUID categoryId,String sort,int page,int size) {
        var filter=CatalogService.filter(q,categoryId,categories);
        var result=products.publicList(seller,filter.pattern(),filter.anyCategory(),filter.categories(),CatalogService.page(page,size,sort,true));
        var summaries=companies.summaries(result.stream().map(p->p.seller()).distinct().toList());
        var imageIds=CatalogService.imageIds(images,result.getContent());
        return new CatalogDtos.Page<>(
            result.stream().map(p->ProductMapper.publicDto(p,summaries.get(p.seller()).name(),imageIds.getOrDefault(p.id(),List.of()))).toList(),
            page,size,result.getTotalElements());
    }
    public CatalogDtos.PublicProduct get(UUID id) {
        var p=products.publicDetail(id).orElseThrow(CatalogService::notFound);
        return ProductMapper.publicDto(p,companies.summary(p.seller()).name(),
            CatalogService.imageIds(images,List.of(p)).getOrDefault(p.id(),List.of()));
    }
    public CatalogDtos.ImageContent image(UUID id,UUID imageId,Integer size) {
        products.publicDetail(id).orElseThrow(CatalogService::notFound);
        return CatalogService.content(images,store,id,imageId,size);
    }
    public List<CatalogDtos.CategoryNode> categories() {
        var all=categories.findAllByOrderByPositionAscCodeAsc();
        var children=new HashMap<UUID,List<CatalogDtos.CategoryNode>>();
        for(Category c:all) {
            if(c.isLeaf()) children.computeIfAbsent(c.parentId(),key->new ArrayList<>()).add(new CatalogDtos.CategoryNode(c.id(),c.code(),c.name(),List.of()));
        }
        return all.stream().filter(c->!c.isLeaf())
            .map(c->new CatalogDtos.CategoryNode(c.id(),c.code(),c.name(),children.getOrDefault(c.id(),List.of()))).toList();
    }
}
