package com.jollytailor.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Production DataSource configuration for PostgreSQL.
 *
 * Automatically parses and supports:
 * 1. Render / Heroku DATABASE_URL format (postgres://user:pass@host:port/dbname or postgresql://...)
 * 2. Standard Spring Boot JDBC format (DB_URL / SPRING_DATASOURCE_URL, DB_USERNAME, DB_PASSWORD)
 * 3. Enforces strict fail-fast validation if production database credentials are missing.
 * 4. Disallows H2 file/memory databases in production to protect data persistence on Render.
 */
@Configuration
@Profile("prod")
public class ProductionDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductionDataSourceConfig.class);

    @Value("${DATABASE_URL:${DB_URL:${SPRING_DATASOURCE_URL:}}}")
    private String configuredUrl;

    @Value("${DB_USERNAME:${SPRING_DATASOURCE_USERNAME:}}")
    private String configuredUsername;

    @Value("${DB_PASSWORD:${SPRING_DATASOURCE_PASSWORD:}}")
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
                "For the 'prod' profile on Render, please configure PostgreSQL credentials via environment variables: " +
                "either DATABASE_URL (Render PostgreSQL URL), or DB_URL (jdbc:postgresql://...) with DB_USERNAME and DB_PASSWORD. " +
                "Local H2 file storage is disabled in production to protect data integrity on Render."
            );
        }

        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("jdbc:h2:") || trimmed.contains(":h2:")) {
            throw new IllegalStateException(
                "H2 database is not allowed in production ('prod' profile). " +
                "Please configure PostgreSQL via DATABASE_URL or DB_URL to ensure data persistence on Render."
            );
        }

        // Support Render / Heroku / Supabase style postgres:// and postgresql:// URIs
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

                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
                if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                    jdbcUrl += "?" + uri.getQuery();
                }

                String user = rawUser;
                String pass = rawPass;

                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isBlank()) {
                    String[] parts = userInfo.split(":", 2);
                    if (user == null || user.isBlank()) {
                        user = parts[0];
                    }
                    if ((pass == null || pass.isBlank()) && parts.length > 1) {
                        pass = parts[1];
                    }
                }

                return new ResolvedDbConfig(jdbcUrl, user, pass);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Failed to parse DATABASE_URL as a valid URI: " + rawUrl, e);
            }
        }

        // Standard JDBC URL (e.g. jdbc:postgresql://...)
        if (!trimmed.startsWith("jdbc:postgresql:")) {
            log.warn("Production database URL is not a standard jdbc:postgresql URL: {}", trimmed);
        }

        return new ResolvedDbConfig(trimmed, rawUser, rawPass);
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        ResolvedDbConfig config = resolve(configuredUrl, configuredUsername, configuredPassword);

        if (config.username == null || config.username.isBlank()) {
            throw new IllegalStateException(
                "Production database username is missing! " +
                "Please configure DB_USERNAME (or provide it inside DATABASE_URL)."
            );
        }

        log.info("Configuring production PostgreSQL DataSource: jdbcUrl={}, user={}", config.jdbcUrl, config.username);

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
        hikari.setPoolName("JollyTailorProdHikariPool");

        return new HikariDataSource(hikari);
    }
}
