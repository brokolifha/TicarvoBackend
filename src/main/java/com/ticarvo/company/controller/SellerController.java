package com.ticarvo.company.controller;
import com.ticarvo.company.dto.SellerPage;
import com.ticarvo.company.dto.SellerProfile;
import com.ticarvo.company.dto.SellerResponse;
import com.ticarvo.company.dto.UpdateSellerProfileRequest;
import com.ticarvo.company.service.SellerDirectoryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class SellerController {
    private final SellerDirectoryService sellers;
    public SellerController(SellerDirectoryService sellers) { this.sellers = sellers; }
    @GetMapping("/sellers")
    public SellerPage list(@RequestParam(required=false) String q, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return sellers.list(q, page, size); }
    @GetMapping("/sellers/{id}")
    public SellerResponse get(@PathVariable UUID id) { return sellers.get(id); }
    @GetMapping("/companies/{id}/shipping-terms")
    public com.ticarvo.company.dto.ShippingTerms shippingTerms(@PathVariable UUID id, Authentication auth) { return sellers.shippingTerms(id, auth); }
    @PutMapping("/companies/{id}/shipping-terms")
    public com.ticarvo.company.dto.ShippingTerms updateShippingTerms(@PathVariable UUID id,
            @Valid @RequestBody com.ticarvo.company.dto.ShippingTerms request, Authentication auth) { return sellers.updateShippingTerms(id, request, auth); }
    @GetMapping("/companies/{id}/seller-profile")
    public SellerProfile profile(@PathVariable UUID id, Authentication auth) { return sellers.profile(id, auth); }
    @PutMapping("/companies/{id}/seller-profile")
    public SellerProfile update(@PathVariable UUID id,
            @Valid @RequestBody UpdateSellerProfileRequest request, Authentication auth) { return sellers.update(id, request.listed(), auth); }
}
