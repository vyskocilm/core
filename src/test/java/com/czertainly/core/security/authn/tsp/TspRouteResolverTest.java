package com.czertainly.core.security.authn.tsp;

import com.otilm.api.exception.NotFoundException;
import com.czertainly.core.model.signing.TspProfileModel;
import com.czertainly.core.service.SigningProfileService;
import com.czertainly.core.service.TspProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TspRouteResolverTest {

    @Mock private TspProfileService tspProfileService;
    @Mock private SigningProfileService signingProfileService;

    private TspRouteResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TspRouteResolver(tspProfileService, signingProfileService);
    }

    private static TspProfileModel anyProfile() {
        return new TspProfileModel(UUID.randomUUID(), "p", null, true, null, null, List.of(), List.of(), List.of(), null);
    }

    private static MockHttpServletRequest requestWith(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(servletPath);
        return request;
    }

    @Test
    void matches_nullPath_isFalse() {
        assertFalse(resolver.matches(null));
    }

    @Test
    void matches_directSignPath_isTrue() {
        assertTrue(resolver.matches("/v1/protocols/tsp/p1/sign"));
    }

    @Test
    void matches_multiSegmentPath_isFalse() {
        assertFalse(resolver.matches("/v1/protocols/tsp/a/b/sign"));
    }

    @Test
    void matches_nonSignPath_isFalse() {
        assertFalse(resolver.matches("/v1/protocols/tsp/p1/verify"));
    }

    @Test
    void resolve_directRoute_usesTspProfileService() throws NotFoundException {
        TspProfileModel profile = anyProfile();
        when(tspProfileService.resolveTspProfileForAuthentication("p1")).thenReturn(profile);

        Optional<TspProfileModel> resolved = resolver.resolve(requestWith("/v1/protocols/tsp/p1/sign"));

        assertSame(profile, resolved.orElseThrow());
        verifyNoInteractions(signingProfileService);
    }

    @Test
    void resolve_indirectRoute_usesSigningProfileService() throws NotFoundException {
        TspProfileModel profile = anyProfile();
        when(signingProfileService.resolveTspProfileForSigningProfileAuthentication("sp1")).thenReturn(Optional.of(profile));

        Optional<TspProfileModel> resolved = resolver.resolve(requestWith("/v1/protocols/tsp/signingProfiles/sp1/sign"));

        assertSame(profile, resolved.orElseThrow());
        verifyNoInteractions(tspProfileService);
    }

    @Test
    void resolve_nonMatchingPath_isEmptyWithoutTouchingServices() throws NotFoundException {
        Optional<TspProfileModel> resolved = resolver.resolve(requestWith("/v1/protocols/tsp/a/b/sign"));

        assertTrue(resolved.isEmpty());
        verifyNoInteractions(tspProfileService, signingProfileService);
    }
}
