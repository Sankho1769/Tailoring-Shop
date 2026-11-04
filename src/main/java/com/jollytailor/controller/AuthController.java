package com.jollytailor.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, Deque<Long>> loginAttemptTimestamps = new ConcurrentHashMap<>();

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository, JdbcTemplate jdbc) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.jdbc = jdbc;
    }

    public void resetRateLimits() {
        loginAttemptTimestamps.clear();
    }

    private void checkLoginRateLimit(HttpServletRequest request) {
        if (request == null) return;
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        } else if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        if (ip == null || ip.isBlank()) ip = "unknown";

        long now = System.currentTimeMillis();
        Deque<Long> queue = loginAttemptTimestamps.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > 60000L) {
                queue.pollFirst();
            }
            if (queue.size() >= 10) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Please wait a minute before trying again.");
            }
            queue.addLast(now);
        }
    }

    @GetMapping("/csrf")
    public Map<String,String> csrf(CsrfToken token) {
        String tokenVal = token != null ? token.getToken() : "";
        String headerName = token != null ? token.getHeaderName() : "X-XSRF-TOKEN";
        return Map.of("token", tokenVal, "headerName", headerName);
    }

    @PostMapping("/login")
    public Map<String,Object> login(@RequestBody(required = false) Map<String,Object> body, HttpServletRequest request, HttpServletResponse response) {
        checkLoginRateLimit(request);
        String username = body != null && body.get("username") != null ? body.get("username").toString().trim() : "";
        String password = body != null && body.get("password") != null ? body.get("password").toString() : "";
        if (username.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        boolean rememberMe = false;
        if (body != null) {
            Object rm = body.get("rememberMe");
            if (rm == null) rm = body.get("remember_me");
            rememberMe = rm != null && ("true".equalsIgnoreCase(rm.toString()) || Boolean.TRUE.equals(rm));
        }

        try {
            Authentication auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
            HttpSession previous = request.getSession(false);
            if (previous != null) {
                previous.invalidate();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            // Server-side session inactivity configuration & client cookie persistence
            HttpSession session = request.getSession(true);
            boolean secure = isSecureRequest(request);
            if (rememberMe) {
                // Persistent session: 30 days (2,592,000 seconds)
                int persistentSeconds = 30 * 24 * 3600;
                session.setMaxInactiveInterval(persistentSeconds);
                session.setAttribute("REMEMBERED", true);

                jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("JSESSIONID", session.getId());
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setMaxAge(persistentSeconds);
                if (secure) cookie.setSecure(true);
                cookie.setAttribute("SameSite", "Lax");
                response.addCookie(cookie);
            } else {
                // Standard session: 30 minutes (1,800 seconds) session cookie
                session.setMaxInactiveInterval(1800);
                session.setAttribute("REMEMBERED", false);

                jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("JSESSIONID", session.getId());
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setMaxAge(-1); // Browser session cookie
                if (secure) cookie.setSecure(true);
                cookie.setAttribute("SameSite", "Lax");
                response.addCookie(cookie);
            }

            try {
                jdbc.update("UPDATE app_users SET last_login_at=NOW() WHERE username=?", auth.getName());
            } catch (Exception ignored) {}

            String role = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "ADMIN" : "TAILOR";
            String fullName = auth.getName();
            try {
                String fn = jdbc.queryForObject("SELECT full_name FROM app_users WHERE username=?", String.class, auth.getName());
                if (fn != null && !fn.isBlank()) fullName = fn;
            } catch (Exception ignored) {}

            return Map.of("username", auth.getName(), "role", role, "fullName", fullName, "remembered", rememberMe);
        } catch (DisabledException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is inactive. Please contact the administrator.");
        } catch (org.springframework.security.core.AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @GetMapping("/me")
    public Map<String,Object> me(Authentication auth, HttpServletRequest request) {
        if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String role = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "ADMIN" : "TAILOR";
        String fullName = auth.getName();
        try {
            String fn = jdbc.queryForObject("SELECT full_name FROM app_users WHERE username=?", String.class, auth.getName());
            if (fn != null && !fn.isBlank()) fullName = fn;
        } catch (Exception ignored) {}

        boolean remembered = false;
        HttpSession session = request != null ? request.getSession(false) : null;
        if (session != null) {
            Object remAttr = session.getAttribute("REMEMBERED");
            if (remAttr instanceof Boolean b) remembered = b;
        }

        return Map.of("username", auth.getName(), "role", role, "fullName", fullName, "remembered", remembered);
    }

    @PostMapping("/logout")
    public Map<String,String> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        boolean secure = isSecureRequest(request);
        jakarta.servlet.http.Cookie clearCookie = new jakarta.servlet.http.Cookie("JSESSIONID", "");
        clearCookie.setPath("/");
        clearCookie.setHttpOnly(true);
        clearCookie.setMaxAge(0);
        if (secure) clearCookie.setSecure(true);
        clearCookie.setAttribute("SameSite", "Lax");
        response.addCookie(clearCookie);

        return Map.of("message", "Logged out");
    }

    private boolean isSecureRequest(HttpServletRequest req) {
        if (req == null) return false;
        if (req.isSecure()) return true;
        String proto = req.getHeader("X-Forwarded-Proto");
        if (proto != null && "https".equalsIgnoreCase(proto.trim())) return true;
        String ssl = req.getHeader("X-Forwarded-Ssl");
        return ssl != null && "on".equalsIgnoreCase(ssl.trim());
    }
}
