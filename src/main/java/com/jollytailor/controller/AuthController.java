package com.jollytailor.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    public AuthController(AuthenticationManager authenticationManager) { this.authenticationManager = authenticationManager; }

    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,String> body, HttpServletRequest request) {
        String username = body.getOrDefault("username", ""); String password = body.getOrDefault("password", "");
        try {
            Authentication auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
            HttpSession previous = request.getSession(false); if (previous != null) previous.invalidate();
            SecurityContext context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
            HttpSession session = request.getSession(true); session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            String role = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "ADMIN" : "TAILOR";
            return Map.of("username", auth.getName(), "role", role);
        } catch (org.springframework.security.core.AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @GetMapping("/me") public Map<String,Object> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String role = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "ADMIN" : "TAILOR";
        return Map.of("username", auth.getName(), "role", role);
    }

    @PostMapping("/logout") public Map<String,String> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext(); HttpSession session = request.getSession(false); if (session != null) session.invalidate();
        return Map.of("message", "Logged out");
    }
}
