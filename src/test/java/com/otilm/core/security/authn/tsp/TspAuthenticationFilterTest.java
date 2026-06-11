package com.otilm.core.security.authn.tsp;

import com.otilm.api.exception.NotFoundException;
import com.otilm.api.model.core.logging.enums.ActorType;
import com.otilm.api.model.core.logging.enums.AuthMethod;
import com.otilm.api.model.core.signing.TspAuthenticationMethod;
import com.otilm.core.auth.oauth2.PlatformJwtDecoder;
import com.otilm.core.model.signing.TspProfileModel;
import com.otilm.core.security.authn.PlatformAuthenticationException;
import com.otilm.core.security.authn.client.AuthenticationInfo;
import com.otilm.core.security.authn.client.CredentialVerificationCache;
import com.otilm.core.security.authn.client.PlatformAuthenticationClient;
import com.otilm.core.service.SigningProfileService;
import com.otilm.core.service.TspProfileService;
import com.otilm.core.util.AuthHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TspAuthenticationFilterTest {

    private static final String CERT_HEADER_NAME = "ssl-client-cert";

    // PEM wrapping base64("test") — after normalize+decode yields DER bytes [0x74, 0x65, 0x73, 0x74]
    // expected thumbprint = SHA-256("test") = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
    private static final String CERT_HEADER = "-----BEGIN CERTIFICATE-----\ndGVzdA==\n-----END CERTIFICATE-----\n";
    private static final String CERT_THUMBPRINT = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Mock private TspProfileService tspProfileService;
    @Mock private SigningProfileService signingProfileService;
    @Mock private PlatformAuthenticationClient authClient;
    @Mock private PlatformJwtDecoder jwtDecoder;
    @Mock private CredentialVerificationCache credentialCache;
    @Mock private AuthHelper authHelper;

    private TspAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        TspSecurityContextWriter contextWriter = new TspSecurityContextWriter(authHelper);
        filter = new TspAuthenticationFilter(
                new TspRouteResolver(tspProfileService, signingProfileService),
                List.of(
                        new ClientCertificateAuthenticator(authClient, CERT_HEADER_NAME, contextWriter),
                        new BearerTokenAuthenticator(jwtDecoder, authClient, contextWriter),
                        new BasicPasswordAuthenticator(credentialCache, contextWriter)),
                new TspChallengeWriter());
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** Asserts the audit actor MDC reflects the resolved principal (ActorType.USER), not the legacy {@code tsp} system user. */
    private void assertActorIsPrincipal(String expectedUuid, String expectedUsername) {
        assertEquals(ActorType.USER.name(), MDC.get("log_actor_type"));
        assertEquals(expectedUuid, MDC.get("log_actor_uuid"));
        assertEquals(expectedUsername, MDC.get("log_actor_name"));
    }

    private TspProfileModel modelWith(List<TspAuthenticationMethod> methods) {
        return new TspProfileModel(UUID.randomUUID(), "p1", null, true, null, null, List.of(), methods, List.of(), null);
    }

    private TspProfileModel modelWith(List<TspAuthenticationMethod> methods, UUID vaultProfileUuid,
                                      List<TspProfileModel.BasicCredentialRef> credentials) {
        return new TspProfileModel(UUID.randomUUID(), "p1", null, true, null, null, List.of(), methods, credentials, vaultProfileUuid);
    }

    /**
     * Sets both the request URI and the servlet path. The filter anchors its routing on {@code getServletPath()}
     * (context-path-agnostic); these tests run without a context path, so servlet path equals the URI.
     */
    private void setPath(String path) {
        request.setRequestURI(path);
        request.setServletPath(path);
    }

    private static String basicHeader(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    private static String fingerprintOf(String username, String password) {
        try {
            return com.otilm.core.util.SecretsUtil.calculateSecretContentFingerprint(
                    new com.otilm.api.model.connector.secrets.content.BasicAuthSecretContent(username, password));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuthenticationInfo authenticatedInfo() {
        return new AuthenticationInfo(AuthMethod.CERTIFICATE, "uuid-1", "alice",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), "{\"user\":{\"uuid\":\"uuid-1\",\"username\":\"alice\"}}");
    }

    // ---------------------------------------------------------------------
    // Gate / route resolution
    // ---------------------------------------------------------------------

    @Test
    void disallowedMethod_certOnlyProfile_returns401WithoutChallenge() throws Exception {
        // A client-certificate-only profile has no HTTP-level scheme to advertise: the cert is presented to the
        // TLS-terminating proxy, not via a WWW-Authenticate challenge. The response is 401 with the header omitted.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.CLIENT_CERTIFICATE)));
        request.addHeader("Authorization", basicHeader("u", "p"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(response.getHeader("WWW-Authenticate"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
    }

    @Test
    void nullTspProfileUuidOnSigningProfileRoute_rejectsBeforeAuth() throws Exception {
        setPath("/v1/protocols/tsp/signingProfiles/sp1/sign");
        when(signingProfileService.resolveTspProfileForSigningProfileAuthentication("sp1"))
                .thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(response.getHeader("WWW-Authenticate"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
    }

    @Test
    void signingProfileRoute_resolvesLinkedTspProfile() throws Exception {
        setPath("/v1/protocols/tsp/signingProfiles/sp1/sign");
        when(signingProfileService.resolveTspProfileForSigningProfileAuthentication("sp1"))
                .thenReturn(Optional.of(modelWith(List.of(TspAuthenticationMethod.CLIENT_CERTIFICATE))));
        request.addHeader(CERT_HEADER_NAME, CERT_HEADER);
        when(authClient.authenticateByCertificate(CERT_HEADER, CERT_THUMBPRINT)).thenReturn(authenticatedInfo());

        filter.doFilter(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    // ---------------------------------------------------------------------
    // Path confusion / route anchoring
    // ---------------------------------------------------------------------

    @Test
    void multiSegmentTspPath_isNotGatedByFilter() throws Exception {
        // A multi-segment name between the prefix and /sign must not be treated as a single profile name,
        // nor confused with the indirect signingProfiles route.
        setPath("/v1/protocols/tsp/a/b/sign");

        assertTrue(filter.shouldNotFilter(request));

        filter.doFilter(request, response, chain);

        verifyNoInteractions(tspProfileService, signingProfileService);
        assertNotEquals(401, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void trailingSlashTspPath_isNotGatedByFilter() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign/");

        assertTrue(filter.shouldNotFilter(request));

        filter.doFilter(request, response, chain);

        verifyNoInteractions(tspProfileService, signingProfileService);
        assertNotEquals(401, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void nonSignTspPath_isNotGatedByFilter() throws Exception {
        setPath("/v1/protocols/tsp/p1/verify");

        assertTrue(filter.shouldNotFilter(request));

        filter.doFilter(request, response, chain);

        verifyNoInteractions(tspProfileService, signingProfileService);
        assertNotNull(chain.getRequest());
    }

    @Test
    void directNameLookingLikeSigningProfiles_resolvesAsDirectTspProfile() throws Exception {
        // A single-segment direct name that happens to be "signingProfiles" must NOT be routed through the
        // indirect signing-profile resolution: there is no trailing name segment, so it is a plain direct name.
        setPath("/v1/protocols/tsp/signingProfiles/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("signingProfiles"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.CLIENT_CERTIFICATE)));
        request.addHeader(CERT_HEADER_NAME, CERT_HEADER);
        when(authClient.authenticateByCertificate(CERT_HEADER, CERT_THUMBPRINT)).thenReturn(authenticatedInfo());

        filter.doFilter(request, response, chain);

        verify(tspProfileService).resolveTspProfileForAuthentication("signingProfiles");
        verifyNoInteractions(signingProfileService);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    @Test
    void directRouteNotFound_returns401WithoutAuthentication() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenThrow(new NotFoundException("TspProfile", "p1"));
        request.addHeader(CERT_HEADER_NAME, CERT_HEADER);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
        verifyNoInteractions(authClient);
    }

    @Test
    void bearerOnlyProfile_disallowedMethod_advertisesBearerNotBasic() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BEARER_TOKEN)));
        // present Basic, which is NOT in the allowed set -> 401, header must advertise Bearer (not Basic)
        request.addHeader("Authorization", basicHeader("u", "p"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        String header = response.getHeader("WWW-Authenticate");
        assertNotNull(header);
        assertTrue(header.contains("Bearer"), "expected Bearer challenge in: " + header);
        assertFalse(header.contains("Basic"), "did not expect Basic challenge in: " + header);
        assertNull(chain.getRequest());
    }

    // ---------------------------------------------------------------------
    // CLIENT_CERTIFICATE
    // ---------------------------------------------------------------------

    @Test
    void clientCertificate_setsContextFromConnector() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.CLIENT_CERTIFICATE)));
        request.addHeader(CERT_HEADER_NAME, CERT_HEADER);
        when(authClient.authenticateByCertificate(CERT_HEADER, CERT_THUMBPRINT)).thenReturn(authenticatedInfo());

        filter.doFilter(request, response, chain);

        verify(authClient).authenticateByCertificate(CERT_HEADER, CERT_THUMBPRINT);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
        assertActorIsPrincipal("uuid-1", "alice");
    }

    // ---------------------------------------------------------------------
    // BEARER_TOKEN
    // ---------------------------------------------------------------------

    @Test
    void bearerToken_decodesAndAuthenticatesByToken() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BEARER_TOKEN)));
        request.addHeader("Authorization", "Bearer the.jwt.token");
        Jwt jwt = Jwt.withTokenValue("the.jwt.token")
                .header("alg", "none")
                .claim("sub", "alice")
                .claim("jti", "jti-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode("the.jwt.token")).thenReturn(jwt);
        when(authClient.authenticateByToken(any())).thenReturn(authenticatedInfo());

        filter.doFilter(request, response, chain);

        verify(jwtDecoder).decode("the.jwt.token");
        verify(authClient).authenticateByToken(any());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
        assertActorIsPrincipal("uuid-1", "alice");
    }

    // ---------------------------------------------------------------------
    // BASIC_PASSWORD
    // ---------------------------------------------------------------------

    @Test
    void basicPassword_fingerprintMatch_authenticatesAsMappedUser() throws Exception {
        UUID secretUuid = UUID.randomUUID();
        UUID mappedUser = UUID.randomUUID();
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1")).thenReturn(modelWith(
                List.of(TspAuthenticationMethod.BASIC_PASSWORD), UUID.randomUUID(),
                List.of(new TspProfileModel.BasicCredentialRef("alice", secretUuid, mappedUser, fingerprintOf("alice", "s3cret")))));
        request.addHeader("Authorization", basicHeader("alice", "s3cret"));
        when(credentialCache.getMappedUser(secretUuid, "s3cret")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(credentialCache).putSuccess(secretUuid, "s3cret", mappedUser);
        verify(authHelper).authenticateAsUser(mappedUser);
        assertNotNull(chain.getRequest());
    }

    @Test
    void basicPassword_fingerprintMismatch_returns401() throws Exception {
        UUID secretUuid = UUID.randomUUID();
        UUID mappedUser = UUID.randomUUID();
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1")).thenReturn(modelWith(
                List.of(TspAuthenticationMethod.BASIC_PASSWORD), UUID.randomUUID(),
                List.of(new TspProfileModel.BasicCredentialRef("alice", secretUuid, mappedUser, fingerprintOf("alice", "right")))));
        request.addHeader("Authorization", basicHeader("alice", "wrong"));
        when(credentialCache.getMappedUser(secretUuid, "wrong")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(credentialCache, never()).putSuccess(any(), any(), any());
        verify(authHelper, never()).authenticateAsUser(any());
        assertNull(chain.getRequest());
    }

    @Test
    void basicPassword_cacheHit_skipsFingerprintCheck() throws Exception {
        UUID secretUuid = UUID.randomUUID();
        UUID mappedUser = UUID.randomUUID();
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1")).thenReturn(modelWith(
                List.of(TspAuthenticationMethod.BASIC_PASSWORD), UUID.randomUUID(),
                List.of(new TspProfileModel.BasicCredentialRef("alice", secretUuid, mappedUser, "x"))));
        request.addHeader("Authorization", basicHeader("alice", "s3cret"));
        when(credentialCache.getMappedUser(secretUuid, "s3cret")).thenReturn(Optional.of(mappedUser));

        filter.doFilter(request, response, chain);

        verify(credentialCache, never()).putSuccess(any(), any(), any());
        verify(authHelper).authenticateAsUser(mappedUser);
        assertNotNull(chain.getRequest());
    }

    @Test
    void basicPassword_blankPassword_returns401WithoutForwarding() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BASIC_PASSWORD)));
        request.addHeader("Authorization", basicHeader("alice", ""));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(authHelper, never()).authenticateAsUser(any());
        assertNull(chain.getRequest());
    }

    @Test
    void basicPassword_unknownUsername_returns401() throws Exception {
        UUID secretUuid = UUID.randomUUID();
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1")).thenReturn(modelWith(
                List.of(TspAuthenticationMethod.BASIC_PASSWORD), UUID.randomUUID(),
                List.of(new TspProfileModel.BasicCredentialRef("alice", secretUuid, UUID.randomUUID(), "x"))));
        request.addHeader("Authorization", basicHeader("mallory", "s3cret"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(authHelper, never()).authenticateAsUser(any());
        assertNull(chain.getRequest());
    }

    @Test
    void basicMethodEnabled_wwwAuthenticateAdvertisesBasicRealm() throws Exception {
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BASIC_PASSWORD)));
        // present a Bearer token, which is NOT in the allowed set -> 401, header must advertise Basic realm
        request.addHeader("Authorization", "Bearer some.jwt");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        String header = response.getHeader("WWW-Authenticate");
        assertNotNull(header);
        assertTrue(header.contains("Basic realm=\"p1\""), "expected Basic realm in: " + header);
    }

    // ---------------------------------------------------------------------
    // Fail-closed: malformed / hostile input
    // ---------------------------------------------------------------------

    @Test
    void clientCertificate_authClientThrows_failsClosed() throws Exception {
        // CLIENT_CERTIFICATE profile, cert header present, but authenticateByCertificate throws a RuntimeException.
        // The filter must catch it, return 401, and NOT continue the chain.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.CLIENT_CERTIFICATE)));
        request.addHeader(CERT_HEADER_NAME, CERT_HEADER);
        when(authClient.authenticateByCertificate(CERT_HEADER, CERT_THUMBPRINT))
                .thenThrow(new PlatformAuthenticationException("upstream auth failure"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
    }

    @Test
    void clientCertificate_malformedCertHeader_failsClosed() throws Exception {
        // CLIENT_CERTIFICATE profile, but the cert header contains non-base64 content outside the PEM wrapper,
        // causing Base64.getDecoder().decode to throw IllegalArgumentException (a RuntimeException).
        // The filter must catch it and fail closed.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.CLIENT_CERTIFICATE)));
        // "!!not-valid-base64!!" is not decodable — CertificateUtil.normalizeCertificateContent strips the garbage,
        // and Base64 decoding of "!!not-valid-base64!!" (after stripping PEM headers) will throw.
        request.addHeader(CERT_HEADER_NAME, "!!not-valid-base64!!");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
    }

    @Test
    void bearerToken_decodeReturnsNull_failsClosed() throws Exception {
        // BEARER_TOKEN profile, jwtDecoder.decode returns null → filter must return 401, not continue chain.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BEARER_TOKEN)));
        request.addHeader("Authorization", "Bearer the.jwt.token");
        when(jwtDecoder.decode("the.jwt.token")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
        verify(authClient, never()).authenticateByToken(any());
    }

    @Test
    void bearerToken_decodeThrows_failsClosed() throws Exception {
        // BEARER_TOKEN profile, jwtDecoder.decode throws a RuntimeException → filter must catch it and return 401.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BEARER_TOKEN)));
        request.addHeader("Authorization", "Bearer bad.token");
        when(jwtDecoder.decode("bad.token"))
                .thenThrow(new PlatformAuthenticationException("token decode failed"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
        verify(authClient, never()).authenticateByToken(any());
    }

    @Test
    void basicPassword_noColonInCredentials_failsClosedWithoutCallingConnector() throws Exception {
        // BASIC_PASSWORD profile, Authorization header is "Basic <base64 of a string with no colon>".
        // decodeBasicCredentials returns null → filter must return 401 without ever consulting secretService.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BASIC_PASSWORD), UUID.randomUUID(),
                        List.of(new TspProfileModel.BasicCredentialRef("alice", UUID.randomUUID(), UUID.randomUUID(), "x"))));
        // Base64 of "nocredentials" — no colon separator, so decodeBasicCredentials returns null
        String noColon = Base64.getEncoder().encodeToString("nocredentials".getBytes());
        request.addHeader("Authorization", "Basic " + noColon);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
        verify(authHelper, never()).authenticateAsUser(any());
    }

    @Test
    void basicPassword_invalidBase64_failsClosedWithoutCallingConnector() throws Exception {
        // BASIC_PASSWORD profile, Authorization header contains invalid Base64 — decodeBasicCredentials catches
        // IllegalArgumentException and returns null → filter must return 401 without consulting secretService.
        setPath("/v1/protocols/tsp/p1/sign");
        when(tspProfileService.resolveTspProfileForAuthentication("p1"))
                .thenReturn(modelWith(List.of(TspAuthenticationMethod.BASIC_PASSWORD), UUID.randomUUID(),
                        List.of(new TspProfileModel.BasicCredentialRef("alice", UUID.randomUUID(), UUID.randomUUID(), "x"))));
        request.addHeader("Authorization", "Basic !!!not-base64!!!");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(chain.getRequest());
        verify(authHelper, never()).authenticateAsUser(any());
    }
}
