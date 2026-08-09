package com.fams.shared.config;

import com.fams.shared.security.JwtAuthFilter;
import com.fams.shared.security.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    @Value("${app.cors.allowed-origin-patterns}")
    private String corsAllowedOriginPatterns;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, UserDetailsServiceImpl userDetailsService) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Only exercised by browser-based clients calling this API cross-origin (the
     * Expo web dev server, or a LAN-IP origin when testing Expo web from another
     * device). Native iOS/Android requests and the Web Admin (same-origin via the
     * Next.js proxy in next.config.ts) never hit CORS at all.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> patterns = Arrays.stream(corsAllowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .toList();
        configuration.setAllowedOriginPatterns(patterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        // Constructor injection (Spring Security 6.3+) — the no-arg constructor +
        // setUserDetailsService() pair used before this was deprecated in favor of passing
        // the UserDetailsService directly, since a provider without one is a misconfiguration
        // that used to only surface at runtime instead of at construction time.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/health", "/api/v1/auth/db-health",
                                "/api/v1/auth/verify-email", "/api/v1/auth/profile/email/confirm-change",
                                "/api/v1/auth/totp/qr").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        // FIX: đăng ký bằng phone cần endpoint gửi OTP trước — thiếu dòng này
                        // gây 401 Unauthorized vì JwtAuthFilter/anyRequest().authenticated()
                        // chặn mọi request không có Bearer token hợp lệ.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register/send-otp").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/resend-verification").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/otp/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login/totp").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login/google").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh-token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/invitations/validate").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/platform-invitations/validate").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform-invitations/accept").permitAll()
                        .requestMatchers(HttpMethod.GET, "/google-login-test.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized\",\"data\":null}");
                        }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}