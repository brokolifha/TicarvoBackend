package com.ticarvo.company.controller;
import com.ticarvo.company.dto.CompanyPage;
import com.ticarvo.company.dto.CompanyResponse;
import com.ticarvo.company.dto.CreateCompanyRequest;
import com.ticarvo.company.service.CompanyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/companies")
public class CompanyController {
    private final CompanyService companies;
    public CompanyController(CompanyService companies) { this.companies = companies; }
    @PostMapping
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request, Authentication authentication) {
        var result = companies.create(request.name(), authentication);
        return ResponseEntity.created(URI.create("/api/v1/companies/" + result.id())).body(result);
    }
    @GetMapping
    public CompanyPage list(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        return companies.list(page, size, authentication);
    }
    @GetMapping("/{id}")
    public CompanyResponse get(@PathVariable UUID id, Authentication authentication) { return companies.get(id, authentication); }
}
