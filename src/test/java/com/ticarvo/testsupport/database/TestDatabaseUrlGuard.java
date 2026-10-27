package com.ticarvo.testsupport.database;


import java.net.URI;

/** Destructive migration tests can target only the explicitly provisioned disposable schema. */
public final class TestDatabaseUrlGuard {
    private TestDatabaseUrlGuard() {}
    public static String upgradeUrl(String jdbcUrl) {
        final URI source;
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            source = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException invalid) {
            throw rejected();
        }
        if (!"mysql".equals(source.getScheme())
                || !"127.0.0.1".equals(source.getHost())
                || source.getPort() != 56128
                || !"127.0.0.1:56128".equals(source.getRawAuthority())
                || !"/ticarvo_m2_test".equals(source.getRawPath())
                || source.getRawUserInfo() != null
                || source.getRawFragment() != null) {
            throw rejected();
        }
        // Do not copy arbitrary JDBC query parameters: they can alter driver/connection behavior.
        return "jdbc:mysql://127.0.0.1:56128/ticarvo_m2_upgrade_test"
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }
    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("Upgrade tests require exactly the dedicated 127.0.0.1:56128/ticarvo_m2_test database.");
    }
}
