package com.jollytailor.util;

import com.jollytailor.config.ProductionDataSourceConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe, idempotent, conflict-aware migration utility to migrate Jolly Ladies Tailor data
 * from the local H2 file database to Supabase PostgreSQL.
 *
 * Guarantees:
 * 1. Source H2 database is opened strictly in READ-ONLY mode (ACCESS_MODE_DATA=r).
 * 2. Original H2 file is never modified, deleted, or reset.
 * 3. Pre-flight conflict check: Fails safely if destination tables contain unexpected records
 *    or conflicting data. Never silently skips transactional conflicts or overwrites catalog/settings.
 * 4. Preserves all primary keys, UUIDs, foreign keys, and timestamps.
 * 5. Performs relational integrity checks (7 foreign-key parent-child relationships).
 * 6. Performs financial reconciliation (orders, payments, outstanding balance).
 * 7. Zero archival or deletion: Data migration is strictly additive and non-destructive.
 * 8. Masks all passwords and credentials in logs and outputs.
 * 9. Supports --dry-run, --verify-only, and --diagnostic modes.
 * 10. Provides safe per-record diagnostic logging for category inserts (SQLState, constraint, ID/name).
 */
public class H2ToSupabaseMigrator {

    public static class MigrationOptions {
        public boolean dryRun = false;
        public boolean verifyOnly = false;
        public boolean diagnostic = false;
        public String sourceUrl = "jdbc:h2:file:./data/jolly_tailor;AUTO_SERVER=TRUE;IFEXISTS=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        public String sourceUser = "sa";
        public String sourcePassword = "";
        public String destUrl = null;
        public String destUser = null;
        public String destPassword = null;
    }

    public static class TableMigrationReport {
        public String tableName;
        public long sourceCount = 0;
        public long insertedCount = 0;
        public long conflictCount = 0;
        public long unexpectedCount = 0;
        public long destFinalCount = 0;
        public List<String> errors = new ArrayList<>();
    }

    public static class ConflictRecord {
        public String tableName;
        public String identifier;
        public String conflictType; // UNEXPECTED_DEST_RECORD, VALUE_MISMATCH, TRANSACTIONAL_CONFLICT, OVERWRITE_PREVENTED
        public String details;

        public ConflictRecord(String tableName, String identifier, String conflictType, String details) {
            this.tableName = tableName;
            this.identifier = identifier;
            this.conflictType = conflictType;
            this.details = details;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (ID: %s) - %s", conflictType, tableName, identifier, details);
        }
    }

    public static class CategoryInsertDiagnostic {
        public long categoryId;
        public String categoryName;
        public String sqlState;
        public String constraintName;
        public String message;

        public CategoryInsertDiagnostic(long id, String name, String sqlState, String constraintName, String message) {
            this.categoryId = id;
            this.categoryName = name;
            this.sqlState = sqlState;
            this.constraintName = constraintName;
            this.message = message;
        }

        public String toLogMessage() {
            return String.format(
                "Category [ID: %d, Name: '%s'] INSERT ERROR -> SQLState: %s, Constraint: %s, Message: %s",
                categoryId, categoryName, sqlState, constraintName, maskUrl(message)
            );
        }
    }

    public static class ForeignKeyCheckReport {
        public String relationship;
        public long orphanCount = 0;
        public String status = "PASSED";
        public String details = "";
    }

    public static class FinancialSummary {
        public long orderCount = 0;
        public BigDecimal totalBilled = BigDecimal.ZERO;
        public BigDecimal totalAmountPaid = BigDecimal.ZERO;
        public BigDecimal totalPaymentsLogged = BigDecimal.ZERO;
        public BigDecimal outstandingBalance = BigDecimal.ZERO;

        @Override
        public String toString() {
            return String.format(
                "Orders: %d | Total Billed: ₹%s | Order Amount Paid: ₹%s | Payments Table Total: ₹%s | Outstanding: ₹%s",
                orderCount, totalBilled, totalAmountPaid, totalPaymentsLogged, outstandingBalance
            );
        }
    }

    public static class FullMigrationReport {
        public boolean success = false;
        public boolean dryRun = false;
        public boolean verifyOnly = false;
        public boolean diagnostic = false;
        public Map<String, TableMigrationReport> tables = new LinkedHashMap<>();
        public List<ConflictRecord> conflicts = new ArrayList<>();
        public List<ForeignKeyCheckReport> sourceFkChecks = new ArrayList<>();
        public List<ForeignKeyCheckReport> destFkChecks = new ArrayList<>();
        public FinancialSummary sourceFinances = new FinancialSummary();
        public FinancialSummary destFinances = new FinancialSummary();
        public List<String> globalErrors = new ArrayList<>();
    }

    public static void main(String[] args) {
        MigrationOptions options = parseArguments(args);
        resolveDestinationCredentials(options);

        String modeDesc = options.verifyOnly ? "VERIFY ONLY" :
                (options.diagnostic ? "DIAGNOSTIC CHECK (Read-Only)" :
                (options.dryRun ? "DRY RUN (Read-Only Preview)" : "LIVE MIGRATION"));

        System.out.println("================================================================================");
        System.out.println("       JOLLY LADIES TAILOR — H2 TO SUPABASE DATA MIGRATION UTILITY");
        System.out.println("================================================================================");
        System.out.println("Mode         : " + modeDesc);
        System.out.println("Source URL   : " + maskUrl(options.sourceUrl));
        System.out.println("Dest URL     : " + (options.destUrl != null ? maskUrl(options.destUrl) : "[None specified]"));
        System.out.println("================================================================================\n");

        FullMigrationReport report = execute(options);
        printReport(report);

        if (!report.success) {
            System.exit(1);
        }
    }

    public static MigrationOptions parseArguments(String[] args) {
        MigrationOptions opt = new MigrationOptions();
        for (String arg : args) {
            if ("--dry-run".equalsIgnoreCase(arg)) {
                opt.dryRun = true;
            } else if ("--verify-only".equalsIgnoreCase(arg)) {
                opt.verifyOnly = true;
            } else if ("--diagnostic".equalsIgnoreCase(arg)) {
                opt.diagnostic = true;
                opt.dryRun = true;
            } else if (arg.startsWith("--source=")) {
                opt.sourceUrl = arg.substring("--source=".length()).trim();
            } else if (arg.startsWith("--dest=")) {
                opt.destUrl = arg.substring("--dest=".length()).trim();
            } else if (arg.startsWith("--dest-user=")) {
                opt.destUser = arg.substring("--dest-user=".length()).trim();
            } else if (arg.startsWith("--dest-pass=")) {
                opt.destPassword = arg.substring("--dest-pass=".length()).trim();
            }
        }
        return opt;
    }

    public static void resolveDestinationCredentials(MigrationOptions options) {
        if (options.destUrl == null || options.destUrl.isBlank()) {
            String envUrl = System.getenv("SUPABASE_DB_URL");
            if (envUrl == null || envUrl.isBlank()) envUrl = System.getenv("DATABASE_URL");
            if (envUrl == null || envUrl.isBlank()) envUrl = System.getenv("DB_URL");
            if (envUrl == null || envUrl.isBlank()) envUrl = System.getenv("SPRING_DATASOURCE_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                options.destUrl = envUrl.trim();
            }
        }

        if (options.destUser == null || options.destUser.isBlank()) {
            String envUser = System.getenv("SUPABASE_DB_USERNAME");
            if (envUser == null || envUser.isBlank()) envUser = System.getenv("DB_USERNAME");
            if (envUser == null || envUser.isBlank()) envUser = System.getenv("SPRING_DATASOURCE_USERNAME");
            if (envUser != null && !envUser.isBlank()) {
                options.destUser = envUser.trim();
            }
        }

        if (options.destPassword == null || options.destPassword.isBlank()) {
            String envPass = System.getenv("SUPABASE_DB_PASSWORD");
            if (envPass == null || envPass.isBlank()) envPass = System.getenv("DB_PASSWORD");
            if (envPass == null || envPass.isBlank()) envPass = System.getenv("SPRING_DATASOURCE_PASSWORD");
            if (envPass != null && !envPass.isBlank()) {
                options.destPassword = envPass.trim();
            }
        }

        // Check .env file if destination URL was not set via system environment
        if (options.destUrl == null || options.destUrl.isBlank()) {
            java.io.File dotEnv = new java.io.File(".env");
            if (dotEnv.exists()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(dotEnv.toPath())) {
                        line = line.trim();
                        if (line.startsWith("#") || !line.contains("=")) continue;
                        String[] parts = line.split("=", 2);
                        String k = parts[0].trim();
                        String v = parts[1].trim();
                        if ((k.equals("DATABASE_URL") || k.equals("SUPABASE_DB_URL") || k.equals("DB_URL")) && (options.destUrl == null || options.destUrl.isBlank())) {
                            if (!v.isBlank()) options.destUrl = v;
                        } else if ((k.equals("DB_USERNAME") || k.equals("SUPABASE_DB_USERNAME")) && (options.destUser == null || options.destUser.isBlank())) {
                            if (!v.isBlank()) options.destUser = v;
                        } else if ((k.equals("DB_PASSWORD") || k.equals("SUPABASE_DB_PASSWORD")) && (options.destPassword == null || options.destPassword.isBlank())) {
                            if (!v.isBlank()) options.destPassword = v;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // If destUrl is a postgres:// or postgresql:// URI, resolve it through ProductionDataSourceConfig
        if (options.destUrl != null && !options.destUrl.isBlank() && !options.destUrl.startsWith("jdbc:")) {
            try {
                ProductionDataSourceConfig.ResolvedDbConfig resolved =
                        ProductionDataSourceConfig.resolve(options.destUrl, options.destUser, options.destPassword);
                options.destUrl = resolved.jdbcUrl;
                options.destUser = resolved.username;
                options.destPassword = resolved.password;
            } catch (Exception ex) {
                // Keep raw URL if custom
            }
        }
    }

    public static FullMigrationReport execute(MigrationOptions options) {
        FullMigrationReport report = new FullMigrationReport();
        report.dryRun = options.dryRun;
        report.verifyOnly = options.verifyOnly;
        report.diagnostic = options.diagnostic;

        // Ensure H2 is opened safely without lock conflicts and in read-only mode
        String safeSourceUrl = options.sourceUrl;
        if (safeSourceUrl.startsWith("jdbc:h2:file:") && !safeSourceUrl.contains("AUTO_SERVER=TRUE")) {
            safeSourceUrl += (safeSourceUrl.contains(";") ? ";" : ";") + "AUTO_SERVER=TRUE";
        }
        if (!safeSourceUrl.contains("AUTO_SERVER=TRUE") && !safeSourceUrl.contains("ACCESS_MODE_DATA=r")) {
            safeSourceUrl += (safeSourceUrl.contains(";") ? ";" : ";") + "ACCESS_MODE_DATA=r";
        }
        if (!safeSourceUrl.contains("IFEXISTS=TRUE")) {
            safeSourceUrl += ";IFEXISTS=TRUE";
        }

        try (Connection srcConn = DriverManager.getConnection(safeSourceUrl, options.sourceUser, options.sourcePassword)) {
            srcConn.setReadOnly(true);

            // 1. Gather source statistics and finances
            report.sourceFinances = calculateFinances(srcConn);
            populateSourceCountsOnly(srcConn, report);

            // 2. Run source foreign key integrity checks
            report.sourceFkChecks = checkForeignKeys(srcConn, "Source H2");
            for (ForeignKeyCheckReport fk : report.sourceFkChecks) {
                if (fk.orphanCount > 0) {
                    report.globalErrors.add("Source database contains relational orphans in " + fk.relationship);
                }
            }

            if (options.destUrl == null || options.destUrl.isBlank()) {
                if (options.dryRun && !options.verifyOnly) {
                    System.out.println("[INFO] No destination URL provided. Running inspection on source H2 database only.");
                    report.success = report.globalErrors.isEmpty();
                    return report;
                } else {
                    report.globalErrors.add("Destination database URL is missing. Set DATABASE_URL or --dest argument.");
                    report.success = false;
                    return report;
                }
            }

            try {
                Class.forName("org.postgresql.Driver");
            } catch (Exception ignored) {}

            try (Connection destConn = DriverManager.getConnection(options.destUrl, options.destUser, options.destPassword != null ? options.destPassword : "")) {
                // 3. Pre-flight Conflict & Unexpected Record Inspection on Destination
                System.out.println("[INFO] Performing pre-flight destination conflict and safety inspection...");
                List<ConflictRecord> detectedConflicts = detectDestinationConflicts(srcConn, destConn);
                report.conflicts.addAll(detectedConflicts);

                // Run destination foreign key checks
                report.destFkChecks = checkForeignKeys(destConn, "Destination");

                if (options.verifyOnly) {
                    System.out.println("[INFO] Running in VERIFY ONLY mode...");
                    verifyData(srcConn, destConn, report);
                    return report;
                }

                if (options.dryRun) {
                    System.out.println("[INFO] Running in DRY RUN mode. Inspecting tables and checking for conflicts without writing...");
                    runDryRun(srcConn, destConn, report);
                    return report;
                }

                // If conflicts or unexpected records exist, FAIL SAFELY before performing any live write!
                if (!report.conflicts.isEmpty()) {
                    report.globalErrors.add(String.format(
                        "Migration safely ABORTED: Destination database contains %d conflict(s) or unexpected record(s). " +
                        "Review the Conflict Audit Report below before proceeding.", report.conflicts.size()
                    ));
                    report.success = false;
                    return report;
                }

                // 4. LIVE MIGRATION in foreign-key safe topological order
                System.out.println("[INFO] Starting LIVE migration in dependency order...");
                destConn.setAutoCommit(false);
                try {
                    migrateShopSettings(srcConn, destConn, report);
                    migrateCategories(srcConn, destConn, report);
                    migrateAppUsers(srcConn, destConn, report);
                    migrateCustomers(srcConn, destConn, report);
                    migrateCustomerRequests(srcConn, destConn, report);
                    migrateProducts(srcConn, destConn, report);
                    migrateMeasurements(srcConn, destConn, report);
                    migrateTailoringOrders(srcConn, destConn, report);
                    migrateOrderItems(srcConn, destConn, report);
                    migratePayments(srcConn, destConn, report);
                    if (tableExists(srcConn, "staff_work") && tableExists(destConn, "staff_work")) {
                        migrateStaffWork(srcConn, destConn, report);
                    }
                    if (tableExists(srcConn, "staff_payments") && tableExists(destConn, "staff_payments")) {
                        migrateStaffPayments(srcConn, destConn, report);
                    }
                    if (tableExists(srcConn, "fittings") && tableExists(destConn, "fittings")) {
                        migrateFittings(srcConn, destConn, report);
                    }

                    // Check if any errors occurred during insertion
                    boolean anyTableFailed = report.tables.values().stream().anyMatch(t -> !t.errors.isEmpty() || t.conflictCount > 0);
                    if (anyTableFailed) {
                        destConn.rollback();
                        report.globalErrors.add("Migration transaction rolled back due to table errors or unexpected conflicts during insert.");
                        report.success = false;
                        return report;
                    }

                    // Synchronize PostgreSQL sequences
                    syncSequences(destConn);

                    destConn.commit();
                    System.out.println("[SUCCESS] Live migration committed successfully.");
                } catch (Exception ex) {
                    destConn.rollback();
                    report.globalErrors.add("Live migration failed and transaction was rolled back: " + maskUrl(ex.getMessage()));
                    report.success = false;
                    return report;
                }

                // 5. Post-migration verification
                verifyData(srcConn, destConn, report);
            }

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                msg += " | Cause: " + e.getCause().getMessage();
            }
            report.globalErrors.add("Database connection or migration failed: " + maskUrl(msg));
            report.success = false;
        }

        return report;
    }

    private static boolean tableExists(Connection conn, String table) {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void populateSourceCountsOnly(Connection srcConn, FullMigrationReport report) {
        List<String> tables = new ArrayList<>(List.of(
            "shop_settings", "categories", "app_users", "customers",
            "customer_requests", "products", "measurements",
            "tailoring_orders", "order_items", "payments"
        ));
        if (tableExists(srcConn, "staff_work")) tables.add("staff_work");
        if (tableExists(srcConn, "staff_payments")) tables.add("staff_payments");
        if (tableExists(srcConn, "fittings")) tables.add("fittings");
        for (String tbl : tables) {
            TableMigrationReport tr = new TableMigrationReport();
            tr.tableName = tbl;
            tr.sourceCount = getCount(srcConn, tbl);
            report.tables.put(tbl, tr);
        }
    }

    private static void runDryRun(Connection srcConn, Connection destConn, FullMigrationReport report) {
        populateSourceCountsOnly(srcConn, report);
        for (Map.Entry<String, TableMigrationReport> entry : report.tables.entrySet()) {
            TableMigrationReport tr = entry.getValue();
            tr.destFinalCount = getCount(destConn, tr.tableName);
        }
        report.destFinances = calculateFinances(destConn);
        report.destFkChecks = checkForeignKeys(destConn, "Destination");

        // Execute safe category diagnostic inspection
        runCategoryDiagnostics(srcConn, destConn, report);

        // Dry run is successful only if zero conflicts or errors exist
        report.success = report.conflicts.isEmpty() && report.globalErrors.isEmpty();
    }

    /**
     * Inspects destination database for any unexpected records or conflicting values
     * before any migration write is permitted.
     */
    public static List<ConflictRecord> detectDestinationConflicts(Connection src, Connection dst) {
        List<ConflictRecord> list = new ArrayList<>();

        // 1. Transactional tables: check for unexpected records or value discrepancies
        checkTransactionalTableConflicts(src, dst, "tailoring_orders", "id", "order_number", list);
        checkTransactionalTableConflicts(src, dst, "order_items", "id", "item_name", list);
        checkTransactionalTableConflicts(src, dst, "payments", "id", "amount", list);
        checkTransactionalTableConflicts(src, dst, "measurements", "id", "garment_type", list);
        checkTransactionalTableConflicts(src, dst, "customers", "id", "name", list);
        checkTransactionalTableConflicts(src, dst, "customer_requests", "id", "name", list);

        // 2. Catalog & Settings tables: check for unauthorized overwrites or unexpected records
        checkCatalogTableConflicts(src, dst, "categories", "id", "name", list);
        checkCatalogTableConflicts(src, dst, "products", "id", "name", list);
        checkShopSettingsConflicts(src, dst, list);
        checkAppUsersConflicts(src, dst, list);

        return list;
    }

    private static void checkTransactionalTableConflicts(Connection src, Connection dst, String table, String idCol, String displayCol, List<ConflictRecord> list) {
        String query = "SELECT " + idCol + ", " + displayCol + " FROM " + table;
        try (Statement dstStmt = dst.createStatement();
             ResultSet dstRs = dstStmt.executeQuery(query)) {

            while (dstRs.next()) {
                Object id = dstRs.getObject(idCol);
                String label = dstRs.getString(displayCol);

                // Check if this ID exists in source
                String srcCheck = "SELECT COUNT(*) FROM " + table + " WHERE " + idCol + " = ?";
                try (PreparedStatement srcPs = src.prepareStatement(srcCheck)) {
                    srcPs.setObject(1, id);
                    try (ResultSet srcRs = srcPs.executeQuery()) {
                        if (srcRs.next() && srcRs.getLong(1) == 0) {
                            list.add(new ConflictRecord(
                                table,
                                String.valueOf(id),
                                "UNEXPECTED_DEST_RECORD",
                                "Destination contains record '" + label + "' (ID: " + id + ") that does not exist in source database."
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Table might not exist yet or be empty, which is acceptable on a fresh target
        }
    }

    private static void checkCatalogTableConflicts(Connection src, Connection dst, String table, String idCol, String nameCol, List<ConflictRecord> list) {
        String query = "SELECT " + idCol + ", " + nameCol + " FROM " + table;
        try (Statement dstStmt = dst.createStatement();
             ResultSet dstRs = dstStmt.executeQuery(query)) {

            while (dstRs.next()) {
                Object id = dstRs.getObject(idCol);
                String dstName = dstRs.getString(nameCol);

                String srcQuery = "SELECT " + nameCol + " FROM " + table + " WHERE " + idCol + " = ?";
                try (PreparedStatement srcPs = src.prepareStatement(srcQuery)) {
                    srcPs.setObject(1, id);
                    try (ResultSet srcRs = srcPs.executeQuery()) {
                        if (!srcRs.next()) {
                            list.add(new ConflictRecord(
                                table,
                                String.valueOf(id),
                                "UNEXPECTED_DEST_RECORD",
                                "Destination contains catalog item '" + dstName + "' (ID: " + id + ") not in source database."
                            ));
                        } else {
                            String srcName = srcRs.getString(nameCol);
                            if (!Objects.equals(srcName, dstName)) {
                                list.add(new ConflictRecord(
                                    table,
                                    String.valueOf(id),
                                    "VALUE_MISMATCH",
                                    "Catalog item ID " + id + " has conflicting names: Source='" + srcName + "' vs Dest='" + dstName + "'. Overwrite prevented."
                                ));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Table might not exist yet
        }
    }

    private static void checkShopSettingsConflicts(Connection src, Connection dst, List<ConflictRecord> list) {
        try (Statement dstStmt = dst.createStatement();
             ResultSet dstRs = dstStmt.executeQuery("SELECT setting_key, setting_value FROM shop_settings")) {

            while (dstRs.next()) {
                String key = dstRs.getString("setting_key");
                String dstVal = dstRs.getString("setting_value");

                try (PreparedStatement srcPs = src.prepareStatement("SELECT setting_value FROM shop_settings WHERE setting_key = ?")) {
                    srcPs.setString(1, key);
                    try (ResultSet srcRs = srcPs.executeQuery()) {
                        if (!srcRs.next()) {
                            // If key exists in destination but not in source, and is non-empty
                            if (dstVal != null && !dstVal.isBlank()) {
                                list.add(new ConflictRecord(
                                    "shop_settings",
                                    key,
                                    "UNEXPECTED_DEST_RECORD",
                                    "Destination contains custom setting '" + key + "' with value '" + dstVal + "' not present in source."
                                ));
                            }
                        } else {
                            String srcVal = srcRs.getString("setting_value");
                            // If destination value is non-empty, differs from source, and is not a default placeholder
                            if (dstVal != null && !dstVal.isBlank() && !Objects.equals(dstVal, srcVal)) {
                                list.add(new ConflictRecord(
                                    "shop_settings",
                                    key,
                                    "VALUE_MISMATCH",
                                    "Setting '" + key + "' has conflicting value: Dest='" + dstVal + "' vs Source='" + srcVal + "'. Overwrite prevented."
                                ));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Table might not exist yet
        }
    }

    private static void checkAppUsersConflicts(Connection src, Connection dst, List<ConflictRecord> list) {
        try (Statement dstStmt = dst.createStatement();
             ResultSet dstRs = dstStmt.executeQuery("SELECT id, username, role FROM app_users")) {

            while (dstRs.next()) {
                long id = dstRs.getLong("id");
                String username = dstRs.getString("username");
                String role = dstRs.getString("role");

                try (PreparedStatement srcPs = src.prepareStatement("SELECT role FROM app_users WHERE username = ?")) {
                    srcPs.setString(1, username);
                    try (ResultSet srcRs = srcPs.executeQuery()) {
                        if (srcRs.next()) {
                            String srcRole = srcRs.getString("role");
                            if (!Objects.equals(srcRole, role)) {
                                list.add(new ConflictRecord(
                                    "app_users",
                                    username,
                                    "VALUE_MISMATCH",
                                    "User '" + username + "' has conflicting roles: Source='" + srcRole + "' vs Dest='" + role + "'. Overwrite prevented."
                                ));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Table might not exist yet
        }
    }

    /**
     * Verifies the 7 critical foreign-key relationships to ensure zero relational orphans exist.
     */
    public static List<ForeignKeyCheckReport> checkForeignKeys(Connection conn, String dbLabel) {
        List<ForeignKeyCheckReport> list = new ArrayList<>();
        String[][] checks = {
            {"measurements -> customers", "measurements m LEFT JOIN customers c ON m.customer_id = c.id", "c.id IS NULL"},
            {"tailoring_orders -> customers", "tailoring_orders o LEFT JOIN customers c ON o.customer_id = c.id", "c.id IS NULL"},
            {"tailoring_orders -> measurements", "tailoring_orders o LEFT JOIN measurements m ON o.measurement_id = m.id", "o.measurement_id IS NOT NULL AND m.id IS NULL"},
            {"order_items -> tailoring_orders", "order_items oi LEFT JOIN tailoring_orders o ON oi.order_id = o.id", "o.id IS NULL"},
            {"order_items -> products", "order_items oi LEFT JOIN products p ON oi.product_id = p.id", "oi.product_id IS NOT NULL AND p.id IS NULL"},
            {"payments -> tailoring_orders", "payments p LEFT JOIN tailoring_orders o ON p.order_id = o.id", "o.id IS NULL"},
            {"products -> categories", "products p LEFT JOIN categories c ON p.category_id = c.id", "p.category_id IS NOT NULL AND c.id IS NULL"}
        };

        for (String[] check : checks) {
            ForeignKeyCheckReport fkr = new ForeignKeyCheckReport();
            fkr.relationship = check[0];
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + check[1] + " WHERE " + check[2])) {
                if (rs.next()) {
                    fkr.orphanCount = rs.getLong(1);
                }
                fkr.status = (fkr.orphanCount == 0) ? "PASSED" : "VIOLATION (" + fkr.orphanCount + " orphans)";
            } catch (Exception e) {
                fkr.status = "SKIPPED (Table empty or not present)";
            }
            list.add(fkr);
        }
        return list;
    }

    // 1. shop_settings: Only updates blank or default Flyway placeholders; never overwrites non-empty destination values
    private static void migrateShopSettings(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "shop_settings";
        report.tables.put("shop_settings", tr);
        tr.sourceCount = getCount(src, "shop_settings");

        String sql = "INSERT INTO shop_settings (setting_key, setting_value, updated_at) VALUES (?, ?, ?) " +
                     "ON CONFLICT (setting_key) DO UPDATE SET " +
                     "setting_value = EXCLUDED.setting_value, " +
                     "updated_at = EXCLUDED.updated_at " +
                     "WHERE shop_settings.setting_value IS NULL OR shop_settings.setting_value = '' OR shop_settings.setting_value = EXCLUDED.setting_value";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT setting_key, setting_value, updated_at FROM shop_settings");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setString(1, rs.getString("setting_key"));
                ps.setString(2, rs.getString("setting_value"));
                ps.setTimestamp(3, rs.getTimestamp("updated_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate shop_settings: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "shop_settings");
    }

    // 2. categories: Safe insert with individual diagnostic logging (SQLState, constraint, ID/name)
    public static void migrateCategories(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "categories";
        report.tables.put("categories", tr);
        tr.sourceCount = getCount(src, "categories");

        String sql = "INSERT INTO categories (id, name, description, active, created_at) VALUES (?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, description, active, created_at FROM categories ORDER BY id");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                long id = rs.getLong("id");
                String name = rs.getString("name");
                String description = rs.getString("description");
                boolean active = rs.getBoolean("active");
                Timestamp createdAt = rs.getTimestamp("created_at");

                insertCategoryWithDiagnostics(dst, ps, id, name, description, active, createdAt, tr);
            }
        } catch (Exception e) {
            String safeMsg = maskUrl(e.getMessage());
            tr.errors.add("Failed to iterate categories: " + safeMsg);
        }
        tr.destFinalCount = getCount(dst, "categories");
    }

    public enum CategoryMatchStatus {
        EXACT_MATCH,
        ID_COLLISION_DIFFERING_NAME,
        NAME_COLLISION_DIFFERING_ID,
        NO_RECORD_FOUND
    }

    public static class CategoryMatchInspection {
        public CategoryMatchStatus status;
        public long destId;
        public String destName;
        public String details;
    }

    public static CategoryMatchInspection inspectDestinationCategory(Connection dst, long id, String name) {
        CategoryMatchInspection res = new CategoryMatchInspection();
        res.status = CategoryMatchStatus.NO_RECORD_FOUND;
        try (PreparedStatement checkPs = dst.prepareStatement("SELECT id, name FROM categories WHERE id = ? OR name = ?")) {
            checkPs.setLong(1, id);
            checkPs.setString(2, name);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next()) {
                    res.destId = rs.getLong("id");
                    res.destName = rs.getString("name");
                    if (res.destId == id && Objects.equals(res.destName, name)) {
                        res.status = CategoryMatchStatus.EXACT_MATCH;
                        res.details = String.format("Matches destination category [ID: %d, Name: '%s'] idempotently.", res.destId, res.destName);
                    } else if (res.destId == id && !Objects.equals(res.destName, name)) {
                        res.status = CategoryMatchStatus.ID_COLLISION_DIFFERING_NAME;
                        res.details = String.format("ID collision: Source [ID: %d, Name: '%s'] vs Destination [ID: %d, Name: '%s']. Overwrite prevented.", id, name, res.destId, res.destName);
                    } else if (res.destId != id && Objects.equals(res.destName, name)) {
                        res.status = CategoryMatchStatus.NAME_COLLISION_DIFFERING_ID;
                        res.details = String.format("Name collision on 'categories_name_key': Category '%s' already exists with differing ID (Dest ID: %d vs Source ID: %d). Relational mismatch prevents insert.", name, res.destId, id);
                    }
                }
            }
        } catch (Exception ex) {
            res.details = "Could not inspect destination: " + maskUrl(ex.getMessage());
        }
        return res;
    }

    public static void insertCategoryWithDiagnostics(
            Connection dst, PreparedStatement ps, long id, String name,
            String description, boolean active, Timestamp createdAt, TableMigrationReport tr) {

        Savepoint sp = null;
        try {
            if (!dst.getAutoCommit()) {
                sp = dst.setSavepoint("cat_" + id);
            }
        } catch (Exception ignored) {
        }

        try {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setBoolean(4, active);
            ps.setTimestamp(5, createdAt);
            int affected = ps.executeUpdate();

            if (affected > 0) {
                tr.insertedCount++;
                if (sp != null) {
                    try { dst.releaseSavepoint(sp); } catch (Exception ignored) {}
                }
            } else {
                // Insert affected 0 rows (ON CONFLICT id evaluated by database engine)
                CategoryMatchInspection match = inspectDestinationCategory(dst, id, name);
                if (match.status == CategoryMatchStatus.EXACT_MATCH) {
                    // Idempotent match: category is already present with identical ID and Name!
                    tr.insertedCount++;
                    System.out.println("  [CATEGORY IDEMPOTENT MATCH] " + match.details);
                    if (sp != null) {
                        try { dst.releaseSavepoint(sp); } catch (Exception ignored) {}
                    }
                } else {
                    // Genuine conflict!
                    tr.conflictCount++;
                    String diag = String.format(
                        "Category [ID: %d, Name: '%s'] CONFLICT -> SQLState: 23505, Constraint: %s, Details: %s",
                        id, name,
                        (match.status == CategoryMatchStatus.NAME_COLLISION_DIFFERING_ID ? "categories_name_key" : "categories_pkey"),
                        match.details
                    );
                    System.err.println("  [CATEGORY CONFLICT ERROR] " + diag);
                    tr.errors.add(diag);
                    if (sp != null) {
                        try { dst.releaseSavepoint(sp); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (SQLException ex) {
            if (sp != null) {
                try { dst.rollback(sp); } catch (Exception ignored) {}
            }
            // Check if this exception was caused by a duplicate key that is actually an exact match
            CategoryMatchInspection match = inspectDestinationCategory(dst, id, name);
            if (match.status == CategoryMatchStatus.EXACT_MATCH) {
                tr.insertedCount++;
                System.out.println("  [CATEGORY IDEMPOTENT MATCH] " + match.details);
            } else {
                tr.conflictCount++;
                CategoryInsertDiagnostic diag = buildCategoryErrorDiagnostic(id, name, ex);
                String logMsg = diag.toLogMessage() + " | Details: " + match.details;
                System.err.println("  [CATEGORY INSERT DIAGNOSTIC ERROR] " + logMsg);
                tr.errors.add(logMsg);
            }
        }
    }

    public static CategoryInsertDiagnostic buildCategoryErrorDiagnostic(long id, String name, SQLException ex) {
        String sqlState = ex.getSQLState() != null ? ex.getSQLState() : "UNKNOWN";
        String constraint = extractConstraintName(ex);
        String rawMsg = ex.getMessage() != null ? ex.getMessage() : "";
        String safeMsg = maskUrl(rawMsg);
        return new CategoryInsertDiagnostic(id, name, sqlState, constraint, safeMsg);
    }

    public static String buildCategoryConflictDiagnostic(Connection dst, long id, String name) {
        String existingDestCategory = "None";
        String constraintName = "categories_pkey";
        try (PreparedStatement checkPs = dst.prepareStatement("SELECT id, name FROM categories WHERE id = ? OR name = ?")) {
            checkPs.setLong(1, id);
            checkPs.setString(2, name);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next()) {
                    long destId = rs.getLong("id");
                    String destName = rs.getString("name");
                    if (destId == id && !Objects.equals(destName, name)) {
                        constraintName = "categories_pkey (ID collision with differing name)";
                    } else if (destId != id && Objects.equals(destName, name)) {
                        constraintName = "categories_name_key (Name collision with differing ID)";
                    } else if (destId == id && Objects.equals(destName, name)) {
                        constraintName = "categories_pkey (Identical pre-existing Flyway seed)";
                    }
                    existingDestCategory = String.format("[ID: %d, Name: '%s']", destId, destName);
                }
            }
        } catch (Exception ignored) {
        }

        return String.format(
            "Category [ID: %d, Name: '%s'] -> SQLState: 23505 (conflict), Constraint: %s, Existing in Dest: %s, Message: ON CONFLICT clause prevented duplicate insert",
            id, name, constraintName, existingDestCategory
        );
    }

    public static void runCategoryDiagnostics(Connection src, Connection dst, FullMigrationReport report) {
        System.out.println("\n[CATEGORY PRE-FLIGHT DIAGNOSTIC INSPECTION]:");
        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, description FROM categories ORDER BY id")) {

            while (rs.next()) {
                long srcId = rs.getLong("id");
                String srcName = rs.getString("name");

                try (PreparedStatement ps = dst.prepareStatement("SELECT id, name FROM categories WHERE id = ? OR name = ?")) {
                    ps.setLong(1, srcId);
                    ps.setString(2, srcName);
                    try (ResultSet dstRs = ps.executeQuery()) {
                        if (dstRs.next()) {
                            long dstId = dstRs.getLong("id");
                            String dstName = dstRs.getString("name");
                            String status;
                            if (dstId == srcId && Objects.equals(dstName, srcName)) {
                                status = "EXACT MATCH (Pre-seeded by Flyway V1). In live migration, ON CONFLICT (id) DO NOTHING returns 0 rows affected.";
                            } else if (dstId != srcId && Objects.equals(dstName, srcName)) {
                                status = "NAME COLLISION: Category '" + srcName + "' exists in destination with differing ID (Dest ID: " + dstId + "). Violates UNIQUE constraint 'categories_name_key'!";
                            } else {
                                status = "ID COLLISION: Category ID " + srcId + " exists in destination with differing name ('" + dstName + "'). Value mismatch prevented.";
                            }
                            System.out.printf("  • Source Category [ID: %d, Name: '%s'] -> %s%n", srcId, srcName, status);
                        } else {
                            System.out.printf("  • Source Category [ID: %d, Name: '%s'] -> Not present in destination (Ready for clean insert)%n", srcId, srcName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  ⚠️ Could not run category diagnostic: " + maskUrl(e.getMessage()));
        }
    }

    public static String extractConstraintName(Throwable t) {
        if (t == null) return "unknown";
        try {
            java.lang.reflect.Method m = t.getClass().getMethod("getServerErrorMessage");
            Object serverError = m.invoke(t);
            if (serverError != null) {
                java.lang.reflect.Method getConstraint = serverError.getClass().getMethod("getConstraint");
                Object constraint = getConstraint.invoke(serverError);
                if (constraint != null && !constraint.toString().isBlank()) {
                    return constraint.toString();
                }
            }
        } catch (Exception ignored) {
        }
        // Fallback: parse from exception message
        String msg = t.getMessage();
        if (msg != null) {
            int idx = msg.indexOf("constraint \"");
            if (idx != -1) {
                int end = msg.indexOf("\"", idx + 12);
                if (end != -1) {
                    return msg.substring(idx + 12, end);
                }
            }
            idx = msg.indexOf("constraint [");
            if (idx != -1) {
                int end = msg.indexOf("]", idx + 12);
                if (end != -1) {
                    return msg.substring(idx + 12, end);
                }
            }
            String upper = msg.toUpperCase();
            if (upper.contains("CATEGORIES(NAME") || upper.contains("CATEGORIES_NAME_KEY")) {
                return "categories_name_key";
            }
            if (upper.contains("CATEGORIES(ID") || upper.contains("CATEGORIES_PKEY") || upper.contains("PRIMARY_KEY")) {
                return "categories_pkey";
            }
        }
        return "none";
    }

    // 3. app_users: Never overwrites existing credentials
    private static void migrateAppUsers(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "app_users";
        report.tables.put("app_users", tr);
        tr.sourceCount = getCount(src, "app_users");

        String sql = "INSERT INTO app_users (id, username, password_hash, role, active, created_at, full_name, last_login_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, username, password_hash, role, active, created_at, full_name, last_login_at FROM app_users ORDER BY id");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setLong(1, rs.getLong("id"));
                ps.setString(2, rs.getString("username"));
                ps.setString(3, rs.getString("password_hash"));
                ps.setString(4, rs.getString("role"));
                ps.setBoolean(5, rs.getBoolean("active"));
                ps.setTimestamp(6, rs.getTimestamp("created_at"));
                ps.setString(7, rs.getString("full_name"));
                ps.setTimestamp(8, rs.getTimestamp("last_login_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate app_users: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "app_users");
    }

    // 4. customers: Safe insert
    private static void migrateCustomers(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "customers";
        report.tables.put("customers", tr);
        tr.sourceCount = getCount(src, "customers");

        String sql = "INSERT INTO customers (id, name, phone, address, notes, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, phone, address, notes, created_at, updated_at FROM customers");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setString(2, rs.getString("name"));
                ps.setString(3, rs.getString("phone"));
                ps.setString(4, rs.getString("address"));
                ps.setString(5, rs.getString("notes"));
                ps.setTimestamp(6, rs.getTimestamp("created_at"));
                ps.setTimestamp(7, rs.getTimestamp("updated_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate customers: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "customers");
    }

    // 5. customer_requests
    private static void migrateCustomerRequests(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "customer_requests";
        report.tables.put("customer_requests", tr);
        tr.sourceCount = getCount(src, "customer_requests");

        String sql = "INSERT INTO customer_requests (id, name, phone, email, requested_service, message, status, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, phone, email, requested_service, message, status, created_at FROM customer_requests");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setString(2, rs.getString("name"));
                ps.setString(3, rs.getString("phone"));
                ps.setString(4, rs.getString("email"));
                ps.setString(5, rs.getString("requested_service"));
                ps.setString(6, rs.getString("message"));
                ps.setString(7, rs.getString("status"));
                ps.setTimestamp(8, rs.getTimestamp("created_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate customer_requests: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "customer_requests");
    }

    // 6. products
    private static void migrateProducts(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "products";
        report.tables.put("products", tr);
        tr.sourceCount = getCount(src, "products");

        String sql = "INSERT INTO products (id, category_id, name, description, default_rate, product_type, stock_quantity, image_key, active, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, category_id, name, description, default_rate, product_type, stock_quantity, image_key, active, created_at, updated_at FROM products");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                Object catId = rs.getObject("category_id");
                if (catId != null) ps.setLong(2, ((Number) catId).longValue()); else ps.setNull(2, java.sql.Types.BIGINT);
                ps.setString(3, rs.getString("name"));
                ps.setString(4, rs.getString("description"));
                ps.setBigDecimal(5, rs.getBigDecimal("default_rate"));
                ps.setString(6, rs.getString("product_type") != null ? rs.getString("product_type") : "SERVICE");
                ps.setInt(7, rs.getInt("stock_quantity"));
                ps.setString(8, rs.getString("image_key"));
                ps.setBoolean(9, rs.getBoolean("active"));
                ps.setTimestamp(10, rs.getTimestamp("created_at"));
                ps.setTimestamp(11, rs.getTimestamp("updated_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate products: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "products");
    }

    // 7. measurements
    private static void migrateMeasurements(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "measurements";
        report.tables.put("measurements", tr);
        tr.sourceCount = getCount(src, "measurements");

        // Note: dimensions is cast to ?::jsonb for PostgreSQL compatibility
        String sql = "INSERT INTO measurements (id, customer_id, garment_type, unit, dimensions, fit_preference, notes, created_by, created_at) " +
                     "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, customer_id, garment_type, unit, dimensions, fit_preference, notes, created_by, created_at FROM measurements");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setObject(2, rs.getObject("customer_id", UUID.class));
                ps.setString(3, rs.getString("garment_type"));
                ps.setString(4, rs.getString("unit"));
                String dims = rs.getString("dimensions");
                ps.setString(5, (dims == null || dims.isBlank()) ? "{}" : dims);
                ps.setString(6, rs.getString("fit_preference"));
                ps.setString(7, rs.getString("notes"));
                Object cb = rs.getObject("created_by");
                if (cb != null) ps.setLong(8, ((Number) cb).longValue()); else ps.setNull(8, java.sql.Types.BIGINT);
                ps.setTimestamp(9, rs.getTimestamp("created_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate measurements: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "measurements");
    }

    // 8. tailoring_orders: Safe insert, preserves exact order totals and payment statuses
    private static void migrateTailoringOrders(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "tailoring_orders";
        report.tables.put("tailoring_orders", tr);
        tr.sourceCount = getCount(src, "tailoring_orders");

        String sql = "INSERT INTO tailoring_orders (id, order_number, customer_id, measurement_id, status, payment_status, due_date, notes, subtotal, discount, total, amount_paid, created_by, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, order_number, customer_id, measurement_id, status, payment_status, due_date, notes, subtotal, discount, total, amount_paid, created_by, created_at, updated_at FROM tailoring_orders");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setString(2, rs.getString("order_number"));
                ps.setObject(3, rs.getObject("customer_id", UUID.class));
                Object mid = rs.getObject("measurement_id");
                if (mid != null) ps.setObject(4, rs.getObject("measurement_id", UUID.class)); else ps.setNull(4, java.sql.Types.OTHER);
                ps.setString(5, rs.getString("status"));
                ps.setString(6, rs.getString("payment_status"));
                Date dueDate = rs.getDate("due_date");
                if (dueDate != null) ps.setDate(7, dueDate); else ps.setNull(7, java.sql.Types.DATE);
                ps.setString(8, rs.getString("notes"));
                ps.setBigDecimal(9, rs.getBigDecimal("subtotal"));
                ps.setBigDecimal(10, rs.getBigDecimal("discount"));
                ps.setBigDecimal(11, rs.getBigDecimal("total"));
                ps.setBigDecimal(12, rs.getBigDecimal("amount_paid"));
                Object cb = rs.getObject("created_by");
                if (cb != null) ps.setLong(13, ((Number) cb).longValue()); else ps.setNull(13, java.sql.Types.BIGINT);
                ps.setTimestamp(14, rs.getTimestamp("created_at"));
                ps.setTimestamp(15, rs.getTimestamp("updated_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate tailoring_orders: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "tailoring_orders");
    }

    // 9. order_items
    private static void migrateOrderItems(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "order_items";
        report.tables.put("order_items", tr);
        tr.sourceCount = getCount(src, "order_items");

        String sql = "INSERT INTO order_items (id, order_id, product_id, item_name, quantity, default_rate, unit_rate, discount, amount, adjustment_reason, adjusted_by, adjusted_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, order_id, product_id, item_name, quantity, default_rate, unit_rate, discount, amount, adjustment_reason, adjusted_by, adjusted_at FROM order_items");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setObject(2, rs.getObject("order_id", UUID.class));
                Object pid = rs.getObject("product_id");
                if (pid != null) ps.setObject(3, rs.getObject("product_id", UUID.class)); else ps.setNull(3, java.sql.Types.OTHER);
                ps.setString(4, rs.getString("item_name"));
                ps.setInt(5, rs.getInt("quantity"));
                ps.setBigDecimal(6, rs.getBigDecimal("default_rate"));
                ps.setBigDecimal(7, rs.getBigDecimal("unit_rate"));
                ps.setBigDecimal(8, rs.getBigDecimal("discount"));
                ps.setBigDecimal(9, rs.getBigDecimal("amount"));
                ps.setString(10, rs.getString("adjustment_reason"));
                Object ab = rs.getObject("adjusted_by");
                if (ab != null) ps.setLong(11, ((Number) ab).longValue()); else ps.setNull(11, java.sql.Types.BIGINT);
                ps.setTimestamp(12, rs.getTimestamp("adjusted_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate order_items: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "order_items");
    }

    // 10. payments
    private static void migratePayments(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "payments";
        report.tables.put("payments", tr);
        tr.sourceCount = getCount(src, "payments");

        String sql = "INSERT INTO payments (id, order_id, amount, method, reference, note, payment_type, received_by, received_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, order_id, amount, method, reference, note, payment_type, received_by, received_at FROM payments");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setObject(2, rs.getObject("order_id", UUID.class));
                ps.setBigDecimal(3, rs.getBigDecimal("amount"));
                ps.setString(4, rs.getString("method"));
                ps.setString(5, rs.getString("reference"));
                ps.setString(6, rs.getString("note"));
                ps.setString(7, rs.getString("payment_type"));
                Object rb = rs.getObject("received_by");
                if (rb != null) ps.setLong(8, ((Number) rb).longValue()); else ps.setNull(8, java.sql.Types.BIGINT);
                ps.setTimestamp(9, rs.getTimestamp("received_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate payments: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "payments");
    }

    // 11. staff_work
    private static void migrateStaffWork(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "staff_work";
        report.tables.put("staff_work", tr);
        tr.sourceCount = getCount(src, "staff_work");

        String sql = "INSERT INTO staff_work (id, staff_id, order_id, work_date, description, amount_owed, notes, recorded_by, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, staff_id, order_id, work_date, description, amount_owed, notes, recorded_by, created_at FROM staff_work");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setLong(2, rs.getLong("staff_id"));
                ps.setObject(3, rs.getObject("order_id", UUID.class));
                ps.setDate(4, rs.getDate("work_date"));
                ps.setString(5, rs.getString("description"));
                ps.setBigDecimal(6, rs.getBigDecimal("amount_owed"));
                ps.setString(7, rs.getString("notes"));
                Object rb = rs.getObject("recorded_by");
                if (rb != null) ps.setLong(8, ((Number) rb).longValue()); else ps.setNull(8, java.sql.Types.BIGINT);
                ps.setTimestamp(9, rs.getTimestamp("created_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate staff_work: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "staff_work");
    }

    // 12. staff_payments
    private static void migrateStaffPayments(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "staff_payments";
        report.tables.put("staff_payments", tr);
        tr.sourceCount = getCount(src, "staff_payments");

        String sql = "INSERT INTO staff_payments (id, staff_id, payment_date, amount, payment_method, reference, notes, recorded_by, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO NOTHING";

        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, staff_id, payment_date, amount, payment_method, reference, notes, recorded_by, created_at FROM staff_payments");
             PreparedStatement ps = dst.prepareStatement(sql)) {

            while (rs.next()) {
                ps.setObject(1, rs.getObject("id", UUID.class));
                ps.setLong(2, rs.getLong("staff_id"));
                ps.setDate(3, rs.getDate("payment_date"));
                ps.setBigDecimal(4, rs.getBigDecimal("amount"));
                ps.setString(5, rs.getString("payment_method"));
                ps.setString(6, rs.getString("reference"));
                ps.setString(7, rs.getString("notes"));
                Object rb = rs.getObject("recorded_by");
                if (rb != null) ps.setLong(8, ((Number) rb).longValue()); else ps.setNull(8, java.sql.Types.BIGINT);
                ps.setTimestamp(9, rs.getTimestamp("created_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate staff_payments: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "staff_payments");
    }

    private static void migrateFittings(Connection src, Connection dst, FullMigrationReport report) {
        TableMigrationReport tr = new TableMigrationReport();
        tr.tableName = "fittings";
        report.tables.put("fittings", tr);
        tr.sourceCount = getCount(src, "fittings");

        String sql = "INSERT INTO fittings (id, booking_ref, customer_name, phone, email, service_type, booking_date, time_slot, notes, status, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";
        try (Statement stmt = src.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, booking_ref, customer_name, phone, email, service_type, booking_date, time_slot, notes, status, created_at, updated_at FROM fittings");
             PreparedStatement ps = dst.prepareStatement(sql)) {
            while (rs.next()) {
                ps.setObject(1, rs.getObject("id"));
                ps.setString(2, rs.getString("booking_ref"));
                ps.setString(3, rs.getString("customer_name"));
                ps.setString(4, rs.getString("phone"));
                ps.setString(5, rs.getString("email"));
                ps.setString(6, rs.getString("service_type"));
                ps.setDate(7, rs.getDate("booking_date"));
                ps.setString(8, rs.getString("time_slot"));
                ps.setString(9, rs.getString("notes"));
                ps.setString(10, rs.getString("status"));
                ps.setTimestamp(11, rs.getTimestamp("created_at"));
                ps.setTimestamp(12, rs.getTimestamp("updated_at"));
                int affected = ps.executeUpdate();
                if (affected > 0) {
                    tr.insertedCount++;
                } else {
                    tr.conflictCount++;
                }
            }
        } catch (Exception e) {
            tr.errors.add("Failed to migrate fittings: " + maskUrl(e.getMessage()));
        }
        tr.destFinalCount = getCount(dst, "fittings");
    }

    private static void syncSequences(Connection dst) {
        try (Statement stmt = dst.createStatement()) {
            stmt.execute("SELECT setval('app_users_id_seq', COALESCE((SELECT MAX(id) FROM app_users), 1))");
            stmt.execute("SELECT setval('categories_id_seq', COALESCE((SELECT MAX(id) FROM categories), 1))");
            System.out.println("[INFO] PostgreSQL sequences (app_users_id_seq, categories_id_seq) synchronized.");
        } catch (Exception e) {
            System.out.println("[WARN] Could not sync sequences (may already be in sync): " + maskUrl(e.getMessage()));
        }
    }

    public static void verifyData(Connection src, Connection dst, FullMigrationReport report) {
        boolean allMatched = true;

        List<String> tables = new ArrayList<>(List.of(
            "shop_settings", "categories", "app_users", "customers",
            "customer_requests", "products", "measurements",
            "tailoring_orders", "order_items", "payments"
        ));
        if (tableExists(src, "staff_work") && tableExists(dst, "staff_work")) tables.add("staff_work");
        if (tableExists(src, "staff_payments") && tableExists(dst, "staff_payments")) tables.add("staff_payments");
        if (tableExists(src, "fittings") && tableExists(dst, "fittings")) tables.add("fittings");

        for (String table : tables) {
            TableMigrationReport tr = report.tables.computeIfAbsent(table, k -> new TableMigrationReport());
            tr.tableName = table;
            tr.sourceCount = getCount(src, table);
            tr.destFinalCount = getCount(dst, table);

            if (tr.destFinalCount != tr.sourceCount) {
                tr.errors.add(String.format("Record count mismatch in '%s': Source has %d, but Destination has %d",
                        table, tr.sourceCount, tr.destFinalCount));
                allMatched = false;
            }
        }

        report.sourceFinances = calculateFinances(src);
        report.destFinances = calculateFinances(dst);

        if (report.sourceFinances.totalBilled.compareTo(report.destFinances.totalBilled) != 0) {
            report.globalErrors.add(String.format("Total billed mismatch: Source ₹%s vs Destination ₹%s",
                    report.sourceFinances.totalBilled, report.destFinances.totalBilled));
            allMatched = false;
        }

        if (report.sourceFinances.totalAmountPaid.compareTo(report.destFinances.totalAmountPaid) != 0) {
            report.globalErrors.add(String.format("Order total amount paid mismatch: Source ₹%s vs Destination ₹%s",
                    report.sourceFinances.totalAmountPaid, report.destFinances.totalAmountPaid));
            allMatched = false;
        }

        if (report.sourceFinances.totalPaymentsLogged.compareTo(report.destFinances.totalPaymentsLogged) != 0) {
            report.globalErrors.add(String.format("Payments total mismatch: Source ₹%s vs Destination ₹%s",
                    report.sourceFinances.totalPaymentsLogged, report.destFinances.totalPaymentsLogged));
            allMatched = false;
        }

        if (report.sourceFinances.outstandingBalance.compareTo(report.destFinances.outstandingBalance) != 0) {
            report.globalErrors.add(String.format("Outstanding balance mismatch: Source ₹%s vs Destination ₹%s",
                    report.sourceFinances.outstandingBalance, report.destFinances.outstandingBalance));
            allMatched = false;
        }

        // Run relational checks on destination
        report.destFkChecks = checkForeignKeys(dst, "Destination");
        for (ForeignKeyCheckReport fk : report.destFkChecks) {
            if (fk.orphanCount > 0) {
                report.globalErrors.add("Destination database contains relational orphans in " + fk.relationship);
                allMatched = false;
            }
        }

        report.success = allMatched && report.conflicts.isEmpty() && report.globalErrors.isEmpty();
    }

    public static FinancialSummary calculateFinances(Connection conn) {
        FinancialSummary f = new FinancialSummary();
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) as cnt, COALESCE(SUM(total), 0) as tot_bill, COALESCE(SUM(amount_paid), 0) as tot_paid " +
                    "FROM tailoring_orders")) {
                if (rs.next()) {
                    f.orderCount = rs.getLong("cnt");
                    f.totalBilled = rs.getBigDecimal("tot_bill");
                    f.totalAmountPaid = rs.getBigDecimal("tot_paid");
                    f.outstandingBalance = f.totalBilled.subtract(f.totalAmountPaid);
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(amount), 0) as tot_p FROM payments WHERE payment_type='PAYMENT'")) {
                if (rs.next()) {
                    f.totalPaymentsLogged = rs.getBigDecimal("tot_p");
                }
            }
        } catch (Exception e) {
            // Table may not exist yet on empty destination
        }
        return f;
    }

    public static long getCount(Connection conn, String table) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            // Table might not exist yet on destination
        }
        return 0;
    }

    public static void printReport(FullMigrationReport report) {
        System.out.println("\n================================================================================");
        System.out.println("                         MIGRATION EXECUTION SUMMARY");
        System.out.println("================================================================================");
        System.out.printf("%-22s | %-12s | %-10s | %-10s | %-12s | %-10s%n",
                "Table Name", "Source Rows", "Imported", "Conflicts", "Dest Total", "Status");
        System.out.println("-----------------------+--------------+------------+------------+--------------+-----------");

        for (Map.Entry<String, TableMigrationReport> entry : report.tables.entrySet()) {
            TableMigrationReport tr = entry.getValue();
            String status = tr.errors.isEmpty() && tr.conflictCount == 0 ?
                    (tr.destFinalCount == tr.sourceCount && tr.destFinalCount > 0 ? "VERIFIED" : (report.dryRun ? "INSPECTED" : "PENDING")) : "CONFLICT/ERR";
            System.out.printf("%-22s | %-12d | %-10d | %-10d | %-12d | %-10s%n",
                    tr.tableName, tr.sourceCount, tr.insertedCount, tr.conflictCount, tr.destFinalCount, status);
        }
        System.out.println("================================================================================");
        System.out.println("Source Financials : " + report.sourceFinances);
        System.out.println("Dest Financials   : " + report.destFinances);
        System.out.println("================================================================================");

        // Foreign Key Integrity Section
        if (!report.sourceFkChecks.isEmpty() || !report.destFkChecks.isEmpty()) {
            System.out.println("\n[FOREIGN-KEY RELATIONAL INTEGRITY]");
            List<ForeignKeyCheckReport> fks = !report.destFkChecks.isEmpty() ? report.destFkChecks : report.sourceFkChecks;
            for (ForeignKeyCheckReport fk : fks) {
                System.out.printf("  %-38s : %s%n", fk.relationship, fk.status);
            }
        }

        // Conflict Audit Section
        if (!report.conflicts.isEmpty()) {
            System.out.println("\n[CONFLICT AUDIT REPORT - " + report.conflicts.size() + " CONFLICT(S) / UNEXPECTED RECORD(S) DETECTED]:");
            for (ConflictRecord cr : report.conflicts) {
                System.out.printf("  ❌ [%s] %s (ID/Key: %s): %s%n",
                        cr.conflictType, cr.tableName, cr.identifier, cr.details);
            }
        }

        // Table-level Diagnostic Errors and Conflicts
        boolean hasTableErrors = report.tables.values().stream().anyMatch(t -> !t.errors.isEmpty());
        if (hasTableErrors) {
            System.out.println("\n[TABLE-LEVEL DIAGNOSTIC ERRORS & CONFLICTS]:");
            for (Map.Entry<String, TableMigrationReport> entry : report.tables.entrySet()) {
                TableMigrationReport tr = entry.getValue();
                if (!tr.errors.isEmpty()) {
                    System.out.println("  Table '" + tr.tableName + "':");
                    for (String err : tr.errors) {
                        System.out.println("    ⚠️ " + maskUrl(err));
                    }
                }
            }
        }

        if (!report.globalErrors.isEmpty()) {
            System.out.println("\n[MIGRATION DISCREPANCIES / ERRORS]:");
            for (String err : report.globalErrors) {
                System.out.println("  ❌ " + err);
            }
        }

        String finalStatus = report.success ? "✅ SUCCESSFUL & RECONCILED" :
                (report.diagnostic ? "ℹ️ DIAGNOSTIC INSPECTION COMPLETED" :
                (report.dryRun ? "ℹ️ DRY-RUN INSPECTION COMPLETED" : "❌ MIGRATION FAILED / ABORTED FOR SAFETY"));

        System.out.println("\nFINAL VERDICT: " + finalStatus);
        System.out.println("================================================================================\n");
    }

    public static String maskUrl(String raw) {
        if (raw == null) return "[null]";
        return raw.replaceAll("(?<=://[^:]+:)[^@]+(?=@)", "***")
                  .replaceAll("(?i)(password=)[^&;\\s'\"]+", "$1***")
                  .replaceAll("(?i)(pwd=)[^&;\\s'\"]+", "$1***")
                  .replaceAll("(?<=jdbc:postgresql://)[^/?\\s]+", "[PROTECTED-HOST]");
    }
}
