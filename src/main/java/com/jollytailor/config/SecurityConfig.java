package com.jollytailor.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return username -> {
            var rows = jdbc.query("SELECT username,password_hash,role,active FROM app_users WHERE username=?",
                (rs, n) -> new Object[]{rs.getString("username"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("active")}, username);
            if (rows.isEmpty()) throw new UsernameNotFoundException("Invalid username or password");
            Object[] row = rows.get(0);
            return User.withUsername((String) row[0]).password((String) row[1]).roles((String) row[2]).disabled(!(Boolean) row[3]).build();
        };
    }

    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http.csrf(c -> c.csrfTokenRepository(csrf))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/tailor.html", "/admin.html", "/css/**", "/js/**", "/favicon.ico", "/error", "/api/auth/csrf", "/api/auth/login", "/api/public/**").permitAll()
                .requestMatchers("/api/admin/**", "/api/media/**").hasRole("ADMIN")
                .requestMatchers("/api/tailor/**").hasAnyRole("ADMIN", "TAILOR")
                .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable());
        return http.build();
    }
}
