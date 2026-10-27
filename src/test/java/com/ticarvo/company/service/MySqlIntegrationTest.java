package com.ticarvo.company.service;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.repository.MembershipRepository;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.service.IdentityService;
import com.ticarvo.shared.exception.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
class MySqlIntegrationTest {
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> required("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> required("TEST_DB_PASSWORD"));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for real MySQL integration tests");
        }
        return value;
    }

    @Autowired IdentityService identity;
    @Autowired CompanyService companies;
    @Autowired MembershipRepository memberships;

    @Test
    @Transactional
    void companyMembershipIsCheckedForEachRequest() {
        String suffix = UUID.randomUUID().toString();
        var a = identity.register(new RegisterRequest("A-" + suffix + "@example.invalid", "Synthetic-M1-Password!95", "Owner A"));
        var b = identity.register(new RegisterRequest("B-" + suffix + "@example.invalid", "Synthetic-M1-Password!95", "Owner B"));
        var authA = new UsernamePasswordAuthenticationToken(a.email(), "", List.of());
        var authB = new UsernamePasswordAuthenticationToken(b.email(), "", List.of());

        var companyA = companies.create("Company A", authA);
        var companyB = companies.create("Company B", authB);
        assertEquals("OWNER", companyA.role());
        assertEquals(1, companies.list(0, 20, authA).totalElements());
        assertEquals(companyA.id(), companies.get(companyA.id(), authA).id());
        assertThrows(ApiException.class, () -> companies.get(companyB.id(), authA));
        assertThrows(ApiException.class, () -> companies.get(UUID.randomUUID(), authA));

        var membership = memberships.findByCompanyIdAndUserId(companyA.id(), a.id()).orElseThrow();
        memberships.delete(membership);
        memberships.flush();
        assertThrows(ApiException.class, () -> companies.get(companyA.id(), authA));
    }
}
