package ro.bitboy.mcpgp.platform.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.Secrets;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Accounts;
import ro.bitboy.mcpgp.platform.service.SignIns;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Spring Security does one thing here: company sign-in with OpenID Connect (spec/platform-api.md §3),
 * the authorization code flow with PKCE, at {@code /oauth2/authorization/oidc} and its callback
 * {@code /login/oauth2/code/oidc}. A successful sign-in becomes the platform's own session cookie, and
 * Spring's session is dropped. Everything else (the API's tokens, the web session's guard, the
 * security headers) stays with {@link AuthFilter} and {@link WebUi.Headers}, so Spring's defaults are off.
 */
@Configuration(proxyBeanMethods = false)
public class SignInSecurity {

    public static final String REGISTRATION = "oidc";
    private static final Logger LOG = LoggerFactory.getLogger(SignInSecurity.class);

    @Bean
    SecurityFilterChain signInChain(HttpSecurity http, PlatformConfig config, Accounts accounts) throws Exception {
        http.csrf(c -> c.disable())                 // AuthFilter guards changes made with the session; the OIDC flow is checked by its state
                .headers(h -> h.disable())          // WebUi.Headers
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        if (config.oidc() != null) {
            ClientRegistrationRepository registrations = new Registration(config);
            DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(o -> o.clientRegistrationRepository(registrations)
                    .authorizationEndpoint(e -> e.authorizationRequestResolver(resolver))
                    .securityContextRepository(new RequestAttributeSecurityContextRepository())
                    .successHandler((request, response, auth) -> signedIn(request, response, auth, config, accounts))
                    .failureHandler((request, response, e) -> {
                        LOG.warn("company sign-in failed: {}", e.getMessage());
                        finish(request, response, "/sign-in?error=sso");
                    }));
        }
        return http.build();
    }

    private static void signedIn(HttpServletRequest request, HttpServletResponse response, Authentication auth, PlatformConfig config,
                                 Accounts accounts) throws IOException {
        OidcUser user = (OidcUser) auth.getPrincipal();
        PlatformConfig.Oidc oidc = config.oidc();
        Set<String> groups = new LinkedHashSet<>();
        Object claim = user.getClaims().get(oidc.groupsClaim());
        if (claim instanceof Collection<?> values) {
            values.forEach(v -> groups.add(String.valueOf(v)));
        } else if (claim instanceof String value) {
            groups.add(value);
        }
        String email = Boolean.TRUE.equals(user.getEmailVerified()) ? user.getEmail() : null;
        String name = user.getPreferredUsername() != null ? user.getPreferredUsername() : email != null ? email : user.getSubject();
        SignIns.Person person = new SignIns.Person("oidc", user.getIssuer() + "|" + user.getSubject(), name, email, groups);
        Access.IssuedToken token;
        try {
            token = accounts.signIns().signIn(person, oidc.roles());
        } catch (SignIns.Refused e) {
            finish(request, response, "/sign-in?error=no-access");
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, AccountsApi.sessionCookie(request, token.value(), Accounts.SESSION).toString());
        finish(request, response, "/");
    }

    /** Spring's session only carried the sign-in's state; the platform's cookie is the session from here. */
    private static void finish(HttpServletRequest request, HttpServletResponse response, String to) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.sendRedirect(to);
    }

    /**
     * The provider's settings come from its discovery document, read at the first sign-in rather than at
     * start, so the platform starts while the provider is down.
     */
    static final class Registration implements ClientRegistrationRepository {
        private final PlatformConfig config;
        private volatile ClientRegistration registration;

        Registration(PlatformConfig config) {
            this.config = config;
        }

        @Override
        public ClientRegistration findByRegistrationId(String id) {
            if (!REGISTRATION.equals(id)) {
                return null;
            }
            ClientRegistration current = registration;
            if (current == null) {
                synchronized (this) {
                    if (registration == null) {
                        registration = build();
                    }
                    current = registration;
                }
            }
            return current;
        }

        private ClientRegistration build() {
            PlatformConfig.Oidc oidc = config.oidc();
            String secret = oidc.clientSecret() == null ? null : Secrets.resolve(oidc.clientSecret(), System.getenv())
                    .orElseThrow(() -> new IllegalStateException("auth.oidc.clientSecret (" + oidc.clientSecret() + ") can't be read"));
            ClientRegistration.Builder builder = ClientRegistrations.fromIssuerLocation(oidc.issuer())
                    .registrationId(REGISTRATION)
                    .clientId(oidc.clientId())
                    .scope(oidc.scopes())
                    .redirectUri((config.publicUrl() == null ? "{baseUrl}" : config.publicUrl()) + "/login/oauth2/code/{registrationId}");
            if (secret == null) {
                builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
            } else {
                builder.clientSecret(secret).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            }
            return builder.build();
        }
    }
}
