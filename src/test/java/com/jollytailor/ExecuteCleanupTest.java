package com.jollytailor;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExecuteCleanupTest {

    @Test
    @Disabled("One-off manual cleanup runner for local H2 database")
    @DisplayName("Execute explicit reversible cleanup on confirmed demo records")
    void executeCleanup() throws Exception {
        String dbUrl = "jdbc:h2:file:./data/jolly_tailor;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        File sqlFile = new File("docs/cleanup_demo_data.sql");
        assertTrue(sqlFile.exists(), "cleanup_demo_data.sql must exist");

        String sqlContent = Files.readString(sqlFile.toPath());
        String[] lines = sqlContent.split("\n");

        Map<String, Integer> affected = new LinkedHashMap<>();

        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "")) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM customers WHERE name = 'Ananya Sen'");
                stmt.executeUpdate("DELETE FROM products WHERE name = 'Custom Silk Kurti'");
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("--")) {
                        continue;
                    }
                    if (line.endsWith(";")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    // Strip inline comment if any
                    int commentIdx = line.indexOf("--");
                    if (commentIdx > 0) {
                        line = line.substring(0, commentIdx).trim();
                    }

                    int count = stmt.executeUpdate(line);
                    String tableName = extractTableName(line);
                    affected.put(tableName, affected.getOrDefault(tableName, 0) + count);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        System.out.println("CLEANUP EXECUTION REPORT:");
        for (Map.Entry<String, Integer> entry : affected.entrySet()) {
            System.out.println("  Table " + entry.getKey() + ": " + entry.getValue() + " rows deleted");
        }
    }

    private String extractTableName(String sql) {
        String upper = sql.toUpperCase();
        if (upper.startsWith("DELETE FROM ")) {
            String sub = upper.substring("DELETE FROM ".length()).trim();
            int spaceIdx = sub.indexOf(" ");
            return spaceIdx > 0 ? sub.substring(0, spaceIdx) : sub;
        }
        return "OTHER";
    }
}
