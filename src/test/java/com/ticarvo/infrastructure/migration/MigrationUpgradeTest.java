package com.ticarvo.infrastructure.migration;
import com.ticarvo.testsupport.database.TestDatabaseUrlGuard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MigrationUpgradeTest {
    @Test void existingV1V2DataSurvivesUpgradeAndRepeatedMigration() throws Exception {
        String base=required("TEST_DB_URL");
        String url=TestDatabaseUrlGuard.upgradeUrl(base);
        String user=required("TEST_DB_USERNAME"),password=required("TEST_DB_PASSWORD");
        // This exact secondary schema is disposable synthetic test data, never the application or M1 database.
        Flyway.configure().dataSource(url,user,password).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(url,user,password).target("2").load().migrate();
        String id=UUID.randomUUID().toString();
        try(var connection=DriverManager.getConnection(url,user,password);
            var statement=connection.prepareStatement("INSERT INTO companies(id,name,created_at) VALUES(?, 'Legacy synthetic company', UTC_TIMESTAMP(6))")) {
            statement.setString(1,id); assertEquals(1,statement.executeUpdate());
        }
        var flyway=Flyway.configure().dataSource(url,user,password).target("3").load();
        assertEquals(1,flyway.migrate().migrationsExecuted);
        assertEquals(0,flyway.migrate().migrationsExecuted);
        try(var connection=DriverManager.getConnection(url,user,password);
            var statement=connection.prepareStatement("SELECT public_seller FROM companies WHERE id=?")) {
            statement.setString(1,id);
            try(var result=statement.executeQuery()) { assertTrue(result.next()); assertFalse(result.getBoolean(1)); }
        }
    }
    private static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required for real MySQL");
        return value;
    }

    @Test void freshV4AndV3DataUpgradeAreSafeAndRepeatable() throws Exception {
        String url=TestDatabaseUrlGuard.upgradeUrl(required("TEST_DB_URL"));
        String user=required("TEST_DB_USERNAME"),password=required("TEST_DB_PASSWORD");
        var latest=Flyway.configure().dataSource(url,user,password).cleanDisabled(false).load();
        latest.clean(); assertEquals(18,latest.migrate().migrationsExecuted); assertEquals(0,latest.migrate().migrationsExecuted);
        latest.clean();Flyway.configure().dataSource(url,user,password).target("3").load().migrate();
        String owner=UUID.randomUUID().toString(),seller=UUID.randomUUID().toString(),buyer=UUID.randomUUID().toString();
        try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()){
            sql.executeUpdate("INSERT INTO app_users VALUES('"+owner+"','upgrade@example.invalid','synthetic-unusable-hash','Legacy',UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO companies(id,name,created_at) VALUES('"+seller+"','Seller',UTC_TIMESTAMP(6)),('"+buyer+"','Buyer',UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO company_memberships VALUES(UUID(),'"+seller+"','"+owner+"','OWNER',UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO trade_relationships VALUES(UUID(),'"+buyer+"','"+seller+"','APPROVED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'"+owner+"','"+owner+"')");
            sql.executeUpdate("INSERT INTO seller_invitations VALUES('"+seller+"',REPEAT('a',64),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY),NULL,'"+owner+"')");
        }
        assertEquals(15,latest.migrate().migrationsExecuted);assertEquals(0,latest.migrate().migrationsExecuted);
        try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()){
            for(String table:java.util.List.of("app_users","company_memberships","trade_relationships","seller_invitations")){
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());assertEquals(1,rows.getLong(1),table);}
            }
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM catalog_products")){assertTrue(rows.next());assertEquals(0,rows.getLong(1));}
            // V5: users that existed before M4.1 must stay able to log in.
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM app_users WHERE email_verified_at IS NULL")){assertTrue(rows.next());assertEquals(0,rows.getLong(1));}
            // V8: 8 top-level and 32 subcategories, every subcategory under an existing parent.
            try(var rows=sql.executeQuery("SELECT SUM(parent_id IS NULL),SUM(parent_id IS NOT NULL),SUM(parent_id IS NOT NULL AND parent_id NOT IN (SELECT id FROM catalog_categories WHERE parent_id IS NULL)) FROM catalog_categories")){
                assertTrue(rows.next());assertEquals(8,rows.getLong(1));assertEquals(32,rows.getLong(2));assertEquals(0,rows.getLong(3));
            }
        }
    }
}
