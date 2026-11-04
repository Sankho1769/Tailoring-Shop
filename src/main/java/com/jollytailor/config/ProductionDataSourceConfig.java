package com.jollytailor.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Production and Supabase DataSource configuration for PostgreSQL.
 *
 * Automatically parses and supports:
 * 1. Supabase / Render / Heroku DATABASE_URL format (postgres://user:pass@host:port/dbname or postgresql://...)
 * 2. Dedicated Supabase environment variables (SUPABASE_DB_URL, SUPABASE_DB_USERNAME, SUPABASE_DB_PASSWORD)
 * 3. Standard Spring Boot JDBC format (DB_URL / SPRING_DATASOURCE_URL, DB_USERNAME, DB_PASSWORD)
 * 4. Automatic SSL enforcement for Supabase and remote PostgreSQL endpoints
 * 5. Supabase pooler compatibility (prepareThreshold=0 for transaction mode port 6543)
 * 6. Enforces strict fail-fast validation if database credentials are missing.
 * 7. Disallows H2 file/memory databases in production/supabase profiles to protect data persistence.
 */
@Configuration
@Profile({"prod", "supabase"})
public class ProductionDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductionDataSourceConfig.class);

    @Value("${SUPABASE_DB_URL:${DATABASE_URL:${DB_URL:${SPRING_DATASOURCE_URL:}}}}")
    private String configuredUrl;

    @Value("${SUPABASE_DB_USERNAME:${DB_USERNAME:${SPRING_DATASOURCE_USERNAME:}}}")
    private String configuredUsername;

    @Value("${SUPABASE_DB_PASSWORD:${DB_PASSWORD:${SPRING_DATASOURCE_PASSWORD:}}}")
    private String configuredPassword;

    @Value("${spring.datasource.hikari.maximum-pool-size:${DB_POOL_SIZE:10}}")
    private int maxPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:${DB_MIN_IDLE:2}}")
    private int minIdle;

    public static class ResolvedDbConfig {
        public final String jdbcUrl;
        public final String username;
        public final String password;

        public ResolvedDbConfig(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }
    }

    public static ResolvedDbConfig resolve(String rawUrl, String rawUser, String rawPass) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalStateException(
                "Production database configuration is missing! " +
                "For the 'prod' or 'supabase' profile, please configure PostgreSQL credentials via environment variables: " +
                "either SUPABASE_DB_URL / DATABASE_URL (PostgreSQL connection URI), or DB_URL (jdbc:postgresql://...) with DB_USERNAME and DB_PASSWORD. " +
                "Local H2 file storage is disabled in production to protect data integrity."
            );
        }

        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("jdbc:h2:") || trimmed.contains(":h2:")) {
            throw new IllegalStateException(
                "H2 database is not allowed in production ('prod' or 'supabase' profile). " +
                "Please configure PostgreSQL via SUPABASE_DB_URL, DATABASE_URL, or DB_URL to ensure data persistence."
            );
        }

        // Support Supabase / Render / Neon / Heroku postgres:// and postgresql:// URIs
        if (trimmed.startsWith("postgres://") || (trimmed.startsWith("postgresql://") && !trimmed.startsWith("jdbc:"))) {
            try {
                String uriString = trimmed;
                if (uriString.startsWith("postgres://")) {
                    uriString = "postgresql://" + uriString.substring("postgres://".length());
                }
                URI uri = new URI(uriString);

                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IllegalStateException("DATABASE_URL must include a valid host: " + rawUrl);
                }

                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath();
                if (path == null || path.length() <= 1) {
                    throw new IllegalStateException("DATABASE_URL must include a database name: " + rawUrl);
                }

                StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                        .append(host)
                        .append(":")
                        .append(port)
                        .append(path);

                String query = uri.getQuery();
                boolean hasQuery = query != null && !query.isBlank();
                if (hasQuery) {
                    jdbcUrl.append("?").append(query);
                }

                // Ensure sslmode=require for Supabase endpoints if not already specified
                boolean isSupabase = host.contains("supabase.co") || host.contains("supabase.com");
                if (isSupabase && (query == null || !query.contains("sslmode="))) {
                    jdbcUrl.append(hasQuery ? "&" : "?").append("sslmode=require");
                    hasQuery = true;
                }

                // Supabase transaction pooler (port 6543) compatibility: disable server-side named prepared statements
                if (port == 6543 && (query == null || !query.contains("prepareThreshold="))) {
                    jdbcUrl.append(hasQuery ? "&" : "?").append("prepareThreshold=0");
                }

                String user = rawUser;
                String pass = rawPass;

                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isBlank()) {
                    String[] parts = userInfo.split(":", 2);
                    if (user == null || user.isBlank()) {
                        user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    }
                    if ((pass == null || pass.isBlank()) && parts.length > 1) {
                        pass = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    }
                }

                return new ResolvedDbConfig(jdbcUrl.toString(), user, pass);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Failed to parse DATABASE_URL as a valid URI: " + rawUrl, e);
            }
        }

        // Standard JDBC URL (e.g. jdbc:postgresql://...)
        String finalJdbcUrl = trimmed;
        String user = rawUser;
        String pass = rawPass;

        if (!trimmed.startsWith("jdbc:postgresql:")) {
            log.warn("Production database URL is not a standard jdbc:postgresql URL: {}", trimmed);
        } else {
            // Check for SSL requirement on Supabase endpoints
            boolean isSupabase = trimmed.contains("supabase.co") || trimmed.contains("supabase.com");
            if (isSupabase && !trimmed.contains("sslmode=")) {
                finalJdbcUrl += (trimmed.contains("?") ? "&" : "?") + "sslmode=require";
            }
            if (trimmed.contains(":6543") && !trimmed.contains("prepareThreshold=")) {
                finalJdbcUrl += (finalJdbcUrl.contains("?") ? "&" : "?") + "prepareThreshold=0";
            }
        }

        return new ResolvedDbConfig(finalJdbcUrl, user, pass);
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        ResolvedDbConfig config = resolve(configuredUrl, configuredUsername, configuredPassword);

        if (config.username == null || config.username.isBlank()) {
            throw new IllegalStateException(
                "Production database username is missing! " +
                "Please configure DB_USERNAME / SUPABASE_DB_USERNAME (or provide it inside DATABASE_URL)."
            );
        }

        log.info("Configuring PostgreSQL DataSource for {}: jdbcUrl={}, user={}",
                config.jdbcUrl.contains("supabase") ? "Supabase" : "Production",
                config.jdbcUrl, config.username);

        HikariConfig hikari = new HikariConfig();
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(config.jdbcUrl);
        hikari.setUsername(config.username);
        hikari.setPassword(config.password != null ? config.password : "");
        hikari.setMaximumPoolSize(maxPoolSize);
        hikari.setMinimumIdle(minIdle);
        hikari.setConnectionTimeout(30000);
        hikari.setIdleTimeout(600000);
        hikari.setMaxLifetime(1800000);
        hikari.setLeakDetectionThreshold(60000);
        hikari.setConnectionTestQuery("SELECT 1");
        hikari.setPoolName("JollyTailorPostgresPool");

        return new HikariDataSource(hikari);
    }
}
