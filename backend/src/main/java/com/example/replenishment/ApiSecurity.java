package com.example.replenishment;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class ApiSecurity {
    @Bean
    UserDetailsService users(JdbcTemplate db) {
        return username -> db.query(
            "SELECT login_id,password_hash,active FROM app_user WHERE login_id=?",
            (rs, n) -> User.withUsername(rs.getString(1)).password(rs.getString(2))
                .disabled(!rs.getBoolean(3)).roles("USER").build(), username
        ).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/internal/wms/events"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/session", "/api/v1/csrf", "/actuator/health", "/internal/wms/events").permitAll()
                .anyRequest().authenticated())
            .formLogin(login -> login.loginProcessingUrl("/api/v1/session")
                .successHandler((req, res, auth) -> res.setStatus(204))
                .failureHandler((req, res, error) -> jsonError(res, 401, "UNAUTHENTICATED")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, error) -> jsonError(res, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((req, res, error) -> jsonError(res, 403,
                    error instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALID" : "FORBIDDEN")))
            .logout(logout -> logout.logoutUrl("/api/v1/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .requestCache(cache -> cache.disable())
            .build();
    }

    private void jsonError(jakarta.servlet.http.HttpServletResponse response, int status, String code)
        throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + code + "\"}");
    }
}
