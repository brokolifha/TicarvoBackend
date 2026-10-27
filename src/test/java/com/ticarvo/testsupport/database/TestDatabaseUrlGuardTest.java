package com.ticarvo.testsupport.database;


import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseUrlGuardTest {
    @Test void onlyExplicitLocalSchemaProducesFixedUpgradeTarget() {
        String expected="jdbc:mysql://127.0.0.1:56128/ticarvo_m2_upgrade_test"
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        assertEquals(expected,TestDatabaseUrlGuard.upgradeUrl("jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test"));
        assertEquals(expected,TestDatabaseUrlGuard.upgradeUrl("jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"));
        assertEquals(expected,TestDatabaseUrlGuard.upgradeUrl("jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test?socketFactory=untrusted&database=other_db"));
    }
    @Test void queryDecoyAndWrongAuthorityOrSchemaNeverReachDatabase() {
        var rejected=List.of(
            "jdbc:mysql://127.0.0.1:3306/other_db?marker=/ticarvo_m2_test?",
            "jdbc:mysql://127.0.0.1:56128/other_db?marker=/ticarvo_m2_test?",
            "jdbc:mysql://127.0.0.1:3306/ticarvo_m2_test?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:56128/ticarvo_m1_test",
            "jdbc:mysql://localhost:56128/ticarvo_m2_test",
            "jdbc:mysql://user@127.0.0.1:56128/ticarvo_m2_test",
            "jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test#fragment",
            "jdbc:mysql://127.0.0.1:56128/ticarvo%5Fm2_test",
            "jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test/../other_db",
            "jdbc:mysql://127.0.0.1:56128/ticarvo_m2_test/extra",
            "jdbc:mysql://127.0.0.1:56128",
            "jdbc:mysql:loadbalance://127.0.0.1:56128/ticarvo_m2_test",
            "https://127.0.0.1:56128/ticarvo_m2_test"
        );
        for (String value:rejected) assertThrows(IllegalArgumentException.class,()->TestDatabaseUrlGuard.upgradeUrl(value));
        assertThrows(IllegalArgumentException.class,()->TestDatabaseUrlGuard.upgradeUrl(null));
    }
}
