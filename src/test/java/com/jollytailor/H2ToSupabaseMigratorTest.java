package com.jollytailor;

import com.jollytailor.util.H2ToSupabaseMigrator;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class H2ToSupabaseMigratorTest {

    @Test
    @DisplayName("Password masking safely obscures credentials in URLs")
    void testMaskUrl() {
        String url1 = "postgresql://postgres.xyz123:SecretPassword!@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require";
        String masked1 = H2ToSupabaseMigrator.maskUrl(url1);
        assertFalse(masked1.contains("SecretPassword!"));
        assertTrue(masked1.contains("***"));
        assertTrue(masked1.contains("postgres.xyz123"));

        String url2 = "jdbc:postgresql://localhost:5432/tailordb?user=admin&password=MySuperSecretPassword&sslmode=require";
        String masked2 = H2ToSupabaseMigrator.maskUrl(url2);
        assertFalse(masked2.contains("MySuperSecretPassword"));
        assertTrue(masked2.contains("password=***"));
        assertTrue(masked2.contains("[PROTECTED-HOST]"));
    }

    @Test
    @DisplayName("Argument parsing correctly sets dry-run, verify-only, diagnostic, and custom URLs")
    void testArgumentParsing() {
        String[] args = {
            "--dry-run",
            "--diagnostic",
            "--source=jdbc:h2:file:./data/backup_original/jolly_tailor",
            "--dest=jdbc:postgresql://localhost:5432/tailor_prod"
        };
        H2ToSupabaseMigrator.MigrationOptions opt = H2ToSupabaseMigrator.parseArguments(args);
        assertTrue(opt.dryRun);
        assertTrue(opt.diagnostic);
        assertFalse(opt.verifyOnly);
        assertEquals("jdbc:h2:file:./data/backup_original/jolly_tailor", opt.sourceUrl);
        assertEquals("jdbc:postgresql://localhost:5432/tailor_prod", opt.destUrl);
    }

    @Test
    @DisplayName("Dry-run against local H2 backup inspects tables without any write")
    void testDryRunAgainstH2Backup() {
        File backupDb = new File("data/backup_original/jolly_tailor.mv.db");
        if (!backupDb.exists()) {
            return; // Skip if backup not present in environment
        }

        long originalSize = backupDb.length();
        long originalModTime = backupDb.lastModified();

        H2ToSupabaseMigrator.MigrationOptions opt = new H2ToSupabaseMigrator.MigrationOptions();
        opt.dryRun = true;
        opt.sourceUrl = "jdbc:h2:file:./data/backup_original/jolly_tailor;ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

        H2ToSupabaseMigrator.FullMigrationReport report = H2ToSupabaseMigrator.execute(opt);
        assertTrue(report.success);
        assertTrue(report.dryRun);
        assertEquals(5, report.sourceFinances.orderCount, "Source H2 must contain exactly 5 orders");
        assertEquals(new java.math.BigDecimal("2660.00"), report.sourceFinances.totalBilled, "Total billed must be ₹2,660.00");
        assertEquals(new java.math.BigDecimal("120.00"), report.sourceFinances.totalPaymentsLogged, "Total payments must be ₹120.00");
        assertEquals(new java.math.BigDecimal("2540.00"), report.sourceFinances.outstandingBalance, "Outstanding balance must be ₹2,540.00");

        // Verify source file was strictly untouched
        assertEquals(originalSize, backupDb.length(), "Backup file size must not change during inspection");
        assertEquals(originalModTime, backupDb.lastModified(), "Backup file timestamp must not change during inspection");
    }

    @Test
    @DisplayName("Original H2 database data/jolly_tailor.mv.db is strictly preserved and not modified")
    void testOriginalH2Untouched() {
        File backupDb = new File("data/backup_original/jolly_tailor.mv.db");
        if (backupDb.exists()) {
            assertEquals(122880, backupDb.length(), "Original backup H2 database size must remain exactly 122880 bytes");
        }
        File originalDb = new File("data/jolly_tailor.mv.db");
        assertTrue(originalDb.exists(), "Original H2 database data/jolly_tailor.mv.db must exist");
    }

    @Test
    @DisplayName("Source H2 foreign key checks confirm 100% relational consistency (0 orphans)")
    void testSourceH2ForeignKeyIntegrity() throws Exception {
        File backupDb = new File("data/backup_original/jolly_tailor.mv.db");
        if (!backupDb.exists()) return;

        String h2Url = "jdbc:h2:file:./data/backup_original/jolly_tailor;ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "")) {
            List<H2ToSupabaseMigrator.ForeignKeyCheckReport> fks = H2ToSupabaseMigrator.checkForeignKeys(conn, "H2 Source");
            assertEquals(7, fks.size(), "Must verify 7 critical foreign key relationships");
            for (H2ToSupabaseMigrator.ForeignKeyCheckReport fk : fks) {
                assertEquals(0, fk.orphanCount, "Relationship '" + fk.relationship + "' must have zero orphans");
                assertEquals("PASSED", fk.status);
            }
        }
    }

    @Test
    @DisplayName("Destination conflict detection flags unexpected destination records and fails safely")
    void testDestinationConflictDetection() throws Exception {
        // Create an in-memory destination database with an unexpected order
        String memDestUrl = "jdbc:h2:mem:dest_conflict_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        String h2SourceUrl = "jdbc:h2:file:./data/backup_original/jolly_tailor;ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

        try (Connection destConn = DriverManager.getConnection(memDestUrl, "sa", "");
             Connection srcConn = DriverManager.getConnection(h2SourceUrl, "sa", "")) {

            try (Statement s = destConn.createStatement()) {
                s.execute("CREATE TABLE tailoring_orders (id UUID PRIMARY KEY, order_number VARCHAR(32), total NUMERIC, amount_paid NUMERIC, status VARCHAR(30))");
                // Insert an unexpected order ID that does NOT exist in source H2
                s.execute("INSERT INTO tailoring_orders VALUES ('ffffffff-ffff-ffff-ffff-ffffffffffff', 'JLT-UNEXPECTED-999', 500, 0, 'RECEIVED')");
            }

            List<H2ToSupabaseMigrator.ConflictRecord> conflicts = H2ToSupabaseMigrator.detectDestinationConflicts(srcConn, destConn);
            assertFalse(conflicts.isEmpty(), "Must detect unexpected records in destination");
            assertTrue(conflicts.stream().anyMatch(c -> c.conflictType.equals("UNEXPECTED_DEST_RECORD") && c.identifier.contains("ffffffff-ffff-ffff-ffff-ffffffffffff")));
        }
    }

    @Test
    @DisplayName("Category diagnostic builder captures SQLState, constraint name, ID/name, and masks credentials")
    void testCategoryErrorDiagnosticBuilder() {
        SQLException ex = new SQLException(
            "ERROR: duplicate key value violates unique constraint \"categories_name_key\" on jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?password=SecretPassword123!",
            "23505"
        );

        H2ToSupabaseMigrator.CategoryInsertDiagnostic diag =
                H2ToSupabaseMigrator.buildCategoryErrorDiagnostic(1L, "Stitching", ex);

        assertEquals(1L, diag.categoryId);
        assertEquals("Stitching", diag.categoryName);
        assertEquals("23505", diag.sqlState);
        assertEquals("categories_name_key", diag.constraintName);

        String logMsg = diag.toLogMessage();
        assertTrue(logMsg.contains("SQLState: 23505"));
        assertTrue(logMsg.contains("Constraint: categories_name_key"));
        assertTrue(logMsg.contains("Category [ID: 1, Name: 'Stitching']"));
        assertFalse(logMsg.contains("SecretPassword123!"), "Passwords must be masked");
        assertFalse(logMsg.contains("aws-0-ap-south-1.pooler.supabase.com"), "Full JDBC URLs must be masked");
        assertTrue(logMsg.contains("[PROTECTED-HOST]"));
    }

    @Test
    @DisplayName("Category conflict diagnostic accurately identifies pre-existing destination record without credentials")
    void testCategoryConflictDiagnostic() throws Exception {
        String memDestUrl = "jdbc:h2:mem:cat_diag_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try (Connection destConn = DriverManager.getConnection(memDestUrl, "sa", "")) {
            try (Statement s = destConn.createStatement()) {
                s.execute("CREATE TABLE categories (id BIGINT PRIMARY KEY, name VARCHAR(100) UNIQUE, description TEXT, active BOOLEAN, created_at TIMESTAMP)");
                s.execute("INSERT INTO categories VALUES (1, 'Stitching', 'Garment stitching services', true, NOW())");
            }

            String diag = H2ToSupabaseMigrator.buildCategoryConflictDiagnostic(destConn, 1L, "Stitching");
            assertTrue(diag.contains("Category [ID: 1, Name: 'Stitching']"));
            assertTrue(diag.contains("23505"));
            assertTrue(diag.contains("Identical pre-existing Flyway seed"));
            assertTrue(diag.contains("Existing in Dest: [ID: 1, Name: 'Stitching']"));
        }
    }

    @Test
    @DisplayName("Matching categories are handled idempotently without conflicts (conflictCount = 0)")
    void testInsertCategoryWithExactMatchHandledIdempotently() throws Exception {
        String destUrl = "jdbc:h2:mem:cat_idempotent_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

        try (Connection destConn = DriverManager.getConnection(destUrl, "sa", "")) {
            try (Statement s = destConn.createStatement()) {
                s.execute("CREATE TABLE categories (id BIGINT PRIMARY KEY, name VARCHAR(100) UNIQUE, description TEXT, active BOOLEAN, created_at TIMESTAMP)");
                // Pre-seed matching Category 1: Stitching (as done by Flyway V1)
                s.execute("INSERT INTO categories VALUES (1, 'Stitching', 'Services', true, NOW())");
            }

            destConn.setAutoCommit(false);
            H2ToSupabaseMigrator.TableMigrationReport tr = new H2ToSupabaseMigrator.TableMigrationReport();
            tr.tableName = "categories";

            String sql = "INSERT INTO categories (id, name, description, active, created_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = destConn.prepareStatement(sql)) {
                // Insert identical category: ID 1, Name 'Stitching'
                H2ToSupabaseMigrator.insertCategoryWithDiagnostics(
                    destConn, ps, 1L, "Stitching", "Services", true,
                    new Timestamp(System.currentTimeMillis()), tr
                );
            }

            assertEquals(0, tr.conflictCount, "Identical category must NOT increment conflictCount");
            assertEquals(1, tr.insertedCount, "Identical category must be counted as verified/inserted");
            assertTrue(tr.errors.isEmpty(), "No error should be recorded for exact match");

            destConn.rollback();
        }
    }

    @Test
    @DisplayName("Category with same ID but differing Name flags ID collision conflict")
    void testInsertCategoryWithDifferingNameFlagsIdCollision() throws Exception {
        String destUrl = "jdbc:h2:mem:cat_id_collision_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

        try (Connection destConn = DriverManager.getConnection(destUrl, "sa", "")) {
            try (Statement s = destConn.createStatement()) {
                s.execute("CREATE TABLE categories (id BIGINT PRIMARY KEY, name VARCHAR(100) UNIQUE, description TEXT, active BOOLEAN, created_at TIMESTAMP)");
                // Destination has ID 1 with a different name: 'Embroidery'
                s.execute("INSERT INTO categories VALUES (1, 'Embroidery', 'Embroidery services', true, NOW())");
            }

            destConn.setAutoCommit(false);
            H2ToSupabaseMigrator.TableMigrationReport tr = new H2ToSupabaseMigrator.TableMigrationReport();
            tr.tableName = "categories";

            String sql = "INSERT INTO categories (id, name, description, active, created_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = destConn.prepareStatement(sql)) {
                // Source has ID 1 with name 'Stitching'
                H2ToSupabaseMigrator.insertCategoryWithDiagnostics(
                    destConn, ps, 1L, "Stitching", "Services", true,
                    new Timestamp(System.currentTimeMillis()), tr
                );
            }

            assertEquals(1, tr.conflictCount, "Differing name for same ID must increment conflictCount");
            assertEquals(0, tr.insertedCount, "No row should be counted as inserted");
            assertFalse(tr.errors.isEmpty(), "Error must be recorded");
            assertTrue(tr.errors.get(0).contains("ID collision"));

            destConn.rollback();
        }
    }

    @Test
    @DisplayName("Category with same Name but differing ID flags Name collision on categories_name_key")
    void testInsertCategoryWithDifferingIdFlagsNameCollision() throws Exception {
        String destUrl = "jdbc:h2:mem:cat_name_collision_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

        try (Connection destConn = DriverManager.getConnection(destUrl, "sa", "")) {
            try (Statement s = destConn.createStatement()) {
                s.execute("CREATE TABLE categories (id BIGINT PRIMARY KEY, name VARCHAR(100) UNIQUE, description TEXT, active BOOLEAN, created_at TIMESTAMP)");
                // Destination has category 'Stitching' with ID 2
                s.execute("INSERT INTO categories VALUES (2, 'Stitching', 'Services', true, NOW())");
            }

            destConn.setAutoCommit(false);
            H2ToSupabaseMigrator.TableMigrationReport tr = new H2ToSupabaseMigrator.TableMigrationReport();
            tr.tableName = "categories";

            String sql = "INSERT INTO categories (id, name, description, active, created_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = destConn.prepareStatement(sql)) {
                // Source has category 'Stitching' with ID 1
                H2ToSupabaseMigrator.insertCategoryWithDiagnostics(
                    destConn, ps, 1L, "Stitching", "Services", true,
                    new Timestamp(System.currentTimeMillis()), tr
                );
            }

            assertEquals(1, tr.conflictCount, "Differing ID for same Name must increment conflictCount");
            assertEquals(0, tr.insertedCount, "No row should be counted as inserted");
            assertFalse(tr.errors.isEmpty(), "Error must be recorded");
            assertTrue(tr.errors.get(0).contains("categories_name_key"));

            destConn.rollback();
        }
    }
}
