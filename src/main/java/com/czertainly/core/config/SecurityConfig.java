package com.czertainly.core.config;

import com.czertainly.core.auth.oauth2.*;
import com.czertainly.core.security.authn.CzertainlyAuthenticationFilter;
import com.czertainly.core.security.authn.client.CredentialVerificationCache;
import com.czertainly.core.security.authn.client.CzertainlyAuthenticationClient;
import com.czertainly.core.security.authn.tsp.BasicPasswordAuthenticator;
import com.czertainly.core.security.authn.tsp.BearerTokenAuthenticator;
import com.czertainly.core.security.authn.tsp.ClientCertificateAuthenticator;
import com.czertainly.core.security.authn.tsp.TspAuthenticationFilter;
import com.czertainly.core.security.authn.tsp.TspAuthenticator;
import com.czertainly.core.security.authn.tsp.TspChallengeWriter;
import com.czertainly.core.security.authn.tsp.TspRouteResolver;
import com.czertainly.core.security.authn.tsp.TspSecurityContextWriter;

import java.util.List;
import com.czertainly.core.service.SigningProfileService;
import com.czertainly.core.service.TspProfileService;
import com.czertainly.core.util.AuthHelper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String CERTIFICATE_HEADER_NAME = "server.ssl.certificate-header-name";

    ProtocolValidationFilter protocolValidationFilter;

    private Environment environment;

    private CzertainlyClientRegistrationRepository clientRegistrationRepository;

    private OAuth2LoginFilter oauth2LoginFilter;

    private JwtDecoder jwtDecoder;

    private CzertainlyAuthenticationClient authenticationClient;

    private CzertainlyAuthenticationSuccessHandler authenticationSuccessHandler;

    private CzertainlyOAuth2FailureHandler failureHandler;

    private CzertainlyJwtAuthenticationConverter jwtAuthenticationConverter;

    private TspProfileService tspProfileService;

    private SigningProfileService signingProfileService;

    private CzertainlyJwtDecoder czertainlyJwtDecoder;

    private CredentialVerificationCache credentialVerificationCache;

    private AuthHelper authHelper;

    @Bean
    @Order(1)
    protected SecurityFilterChain tspSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/v*/protocols/tsp/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .sessionManagement(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2Login(AbstractHttpConfigurer::disable)
                .oauth2Client(AbstractHttpConfigurer::disable)
                .x509(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) -> response.sendError(HttpServletResponse.SC_FORBIDDEN))
                )
                .addFilterBefore(createTspAuthenticationFilter(), X509AuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    protected SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(AuthHelper.getPermitAllEndpoints()).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .accessDeniedHandler((request, response, accessDeniedException) -> response.sendError(HttpServletResponse.SC_FORBIDDEN))
                        .authenticationEntryPoint((request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                )
                .sessionManagement(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .x509(AbstractHttpConfigurer::disable)
                .addFilterBefore(protocolValidationFilter, X509AuthenticationFilter.class)
                .addFilterBefore(createCzertainlyAuthenticationFilter(), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
                        .invalidateHttpSession(true) // Invalidate the session
                        .clearAuthentication(true) // Clear the authentication
                        .deleteCookies(CookieConfig.COOKIE_NAME) // Delete the session cookie
                )
                .oauth2Login(oauth2
                        ->
                        oauth2.successHandler(authenticationSuccessHandler)
                                .failureHandler(failureHandler)
                )
                .oauth2Client(oauth2client -> oauth2client.clientRegistrationRepository(clientRegistrationRepository))
                .addFilterAfter(oauth2LoginFilter, OAuth2LoginAuthenticationFilter.class)
        ;

        return http.build();
    }


    @Bean
    public LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        return new CzertainlyLogoutSuccessHandler(clientRegistrationRepository);
    }

    protected CzertainlyAuthenticationFilter createCzertainlyAuthenticationFilter() {
        return new CzertainlyAuthenticationFilter(authenticationClient, environment.getProperty(CERTIFICATE_HEADER_NAME), environment.getProperty("server.servlet.context-path"));
    }

    protected TspAuthenticationFilter createTspAuthenticationFilter() {
        TspSecurityContextWriter contextWriter = new TspSecurityContextWriter(authHelper);
        // Order matters: a presented client certificate takes precedence over an Authorization header.
        List<TspAuthenticator> authenticators = List.of(
                new ClientCertificateAuthenticator(authenticationClient, environment.getProperty(CERTIFICATE_HEADER_NAME), contextWriter),
                new BearerTokenAuthenticator(czertainlyJwtDecoder, authenticationClient, contextWriter),
                new BasicPasswordAuthenticator(credentialVerificationCache, contextWriter));
        return new TspAuthenticationFilter(
                new TspRouteResolver(tspProfileService, signingProfileService),
                authenticators,
                new TspChallengeWriter());
    }

    // SETTERs

    @Autowired
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Autowired
    public void setProtocolValidationFilter(ProtocolValidationFilter protocolValidationFilter) {
        this.protocolValidationFilter = protocolValidationFilter;
    }

    @Autowired
    public void setAuthenticationClient(CzertainlyAuthenticationClient authenticationClient) {
        this.authenticationClient = authenticationClient;
    }

    @Autowired
    public void setAuthenticationSuccessHandler(CzertainlyAuthenticationSuccessHandler authenticationSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
    }

    @Autowired
    public void setClientRegistrationRepository(CzertainlyClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Autowired
    public void setOauth2LoginFilter(OAuth2LoginFilter oauth2LoginFilter) {
        this.oauth2LoginFilter = oauth2LoginFilter;
    }

    @Autowired
    public void setJwtDecoder(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Autowired
    public void setFailureHandler(CzertainlyOAuth2FailureHandler failureHandler) {
        this.failureHandler = failureHandler;
    }

    @Autowired
    public void setJwtAuthenticationConverter(CzertainlyJwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Autowired
    public void setTspProfileService(TspProfileService tspProfileService) {
        this.tspProfileService = tspProfileService;
    }

    @Autowired
    public void setSigningProfileService(SigningProfileService signingProfileService) {
        this.signingProfileService = signingProfileService;
    }

    @Autowired
    public void setCzertainlyJwtDecoder(CzertainlyJwtDecoder czertainlyJwtDecoder) {
        this.czertainlyJwtDecoder = czertainlyJwtDecoder;
    }

    @Autowired
    public void setCredentialVerificationCache(CredentialVerificationCache credentialVerificationCache) {
        this.credentialVerificationCache = credentialVerificationCache;
    }

    @Autowired
    public void setAuthHelper(AuthHelper authHelper) {
        this.authHelper = authHelper;
    }
}
