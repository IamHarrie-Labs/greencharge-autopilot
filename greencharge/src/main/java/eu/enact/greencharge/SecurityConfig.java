package eu.enact.greencharge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The ENACT Application Controller dependency brings Spring Security onto the
 * classpath, and Spring Boot then locks every endpoint behind a generated
 * password - including the Kubernetes health probes, which fail with 401 and
 * restart the pod.
 *
 * <p>GreenCharge's API is public by design, and the autopilot is only exposed
 * through a ClusterIP service (reached with {@code kubectl port-forward}), so
 * access control is left to the cluster rather than to a password nobody knows.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain openApi(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .build();
    }
}
