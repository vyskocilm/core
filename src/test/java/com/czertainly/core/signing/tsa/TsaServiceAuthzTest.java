package com.czertainly.core.signing.tsa;

import com.otilm.api.interfaces.core.tsp.error.TspException;
import com.otilm.api.interfaces.core.tsp.error.TspFailureInfo;
import com.otilm.api.model.client.signing.profile.scheme.ManagedSigningType;
import com.otilm.api.model.client.signing.profile.scheme.SigningScheme;
import com.otilm.api.model.client.signing.profile.workflow.SigningWorkflowType;
import com.otilm.api.model.core.auth.Resource;
import com.otilm.api.model.core.signing.SigningProtocol;
import com.czertainly.core.model.signing.SigningCertificateBuilder;
import com.czertainly.core.dao.entity.signing.SigningProfile;
import com.czertainly.core.dao.entity.signing.SigningProfileVersion;
import com.czertainly.core.dao.entity.signing.TspProfile;
import com.czertainly.core.dao.repository.signing.SigningProfileRepository;
import com.czertainly.core.dao.repository.signing.SigningProfileVersionRepository;
import com.czertainly.core.dao.repository.signing.TspProfileRepository;
import com.otilm.core.model.auth.ResourceAction;
import com.czertainly.core.model.signing.SigningProfileModel;
import com.czertainly.core.model.signing.resolved.ResolvedManagedTimestampingProfile;
import com.czertainly.core.model.signing.resolved.ResolvedStaticKeyManagedSigning;
import com.czertainly.core.model.signing.timequality.LocalClockTimeQualityConfiguration;
import com.czertainly.core.security.authz.opa.dto.OpaRequestedResource;
import com.czertainly.core.security.authz.opa.dto.OpaResourceAccessResult;
import com.czertainly.core.signing.tsa.messages.TspResponse;
import com.czertainly.core.signing.tsa.resolver.SigningProfileResolverFactory;
import com.czertainly.core.util.BaseSpringBootTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static com.czertainly.core.signing.tsa.messages.TspRequestBuilder.aTspRequest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TsaServiceAuthzTest extends BaseSpringBootTest {

    @Autowired
    private TsaService tsaService;

    @MockitoBean
    private ManagedTimestampEngine managedTimestampEngine;

    @MockitoBean
    private SigningProfileResolverFactory signingProfileResolverFactory;

    @Autowired
    private SigningProfileRepository signingProfileRepository;

    @Autowired
    private SigningProfileVersionRepository signingProfileVersionRepository;

    @Autowired
    private TspProfileRepository tspProfileRepository;

    @BeforeEach
    void stubEngineAndResolver() throws TspException {
        lenient().when(managedTimestampEngine.process(any(), any()))
                .thenReturn(TspResponse.granted(new byte[]{1, 2, 3}));

        lenient().when(signingProfileResolverFactory.resolve(any())).thenAnswer(invocation -> {
            SigningProfileModel<?, ?> model = invocation.getArgument(0);
            return new ResolvedManagedTimestampingProfile(
                    model.uuid(), model.name(), model.description(), model.version(), model.enabled(),
                    List.of(SigningProtocol.TSP), Boolean.FALSE, "1.2.3.4.5",
                    List.of(), List.of(), false, List.of(),
                    LocalClockTimeQualityConfiguration.INSTANCE, null,
                    new ResolvedStaticKeyManagedSigning(SigningCertificateBuilder.valid(), List.of(), null, List.of()));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SigningProfile createTimestampingSigningProfile(String name, boolean enabled) {
        SigningProfile profile = new SigningProfile();
        profile.setName(name);
        profile.setWorkflowType(SigningWorkflowType.TIMESTAMPING);
        profile.setSigningScheme(SigningScheme.MANAGED);
        profile.setLatestVersion(1);
        profile.setEnabled(enabled);
        profile = signingProfileRepository.saveAndFlush(profile);

        SigningProfileVersion version = new SigningProfileVersion();
        version.setSigningProfile(profile);
        version.setVersion(1);
        version.setWorkflowType(SigningWorkflowType.TIMESTAMPING);
        version.setSigningScheme(SigningScheme.MANAGED);
        version.setManagedSigningType(ManagedSigningType.STATIC_KEY);
        version.setAllowedDigestAlgorithms(List.of());
        version.setAllowedPolicyIds(List.of());
        signingProfileVersionRepository.saveAndFlush(version);

        return profile;
    }

    private TspProfile createTspProfileFor(String name, boolean enabled, SigningProfile defaultSigningProfile) {
        TspProfile profile = new TspProfile();
        profile.setName(name);
        profile.setEnabled(enabled);
        profile.setDefaultSigningProfile(defaultSigningProfile);
        return tspProfileRepository.saveAndFlush(profile);
    }

    /** Establish the signing-profile → TSP-profile back-link the indirect route authorizes against. */
    private void linkTspProfile(SigningProfile signingProfile, TspProfile tspProfile) {
        signingProfile.setTspProfile(tspProfile);
        signingProfileRepository.saveAndFlush(signingProfile);
    }

    /** Deny the object-level OPA check only for the given TSP profile UUID + TSP_SIGN action. */
    private void denyTspSignForObject(java.util.UUID forbiddenUuid) {
        OpaResourceAccessResult denied = new OpaResourceAccessResult();
        denied.setAuthorized(false);
        when(opaClient.checkResourceAccess(any(), org.mockito.ArgumentMatchers.argThat(req -> isTspSignFor(req, forbiddenUuid)), any(), any()))
                .thenReturn(denied);
    }

    private static boolean isTspSignFor(OpaRequestedResource req, java.util.UUID uuid) {
        return req != null
                && req.getProperties() != null
                && Resource.TSP_PROFILE.getCode().equals(req.getProperties().get("name"))
                && ResourceAction.TSP_SIGN.getCode().equals(req.getProperties().get("action"))
                && req.getObjectUUIDs() != null
                && req.getObjectUUIDs().contains(uuid.toString());
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    void authorizesObjectLevel_withTspProfileUuid_andTspSignAction() throws Exception {
        SigningProfile signingProfile = createTimestampingSigningProfile("sp-authz", true);
        TspProfile tspProfile = createTspProfileFor("tsp-authz", true, signingProfile);

        tsaService.processTspRequestForTspProfile("tsp-authz", aTspRequest().build());

        verify(opaClient, atLeastOnce()).checkResourceAccess(
                any(),
                org.mockito.ArgumentMatchers.argThat(req -> isTspSignFor(req, tspProfile.getUuid())),
                any(), any());
        verify(managedTimestampEngine).process(any(), any());
    }

    @Test
    void deniesObjectLevel_whenNotAuthorizedForThatTspProfile() {
        SigningProfile signingProfileA = createTimestampingSigningProfile("sp-a", true);
        SigningProfile signingProfileB = createTimestampingSigningProfile("sp-b", true);
        TspProfile tspProfileA = createTspProfileFor("tsp-a", true, signingProfileA);
        createTspProfileFor("tsp-b", true, signingProfileB);

        denyTspSignForObject(tspProfileA.getUuid());

        Assertions.assertThrows(AccessDeniedException.class,
                () -> tsaService.processTspRequestForTspProfile("tsp-a", aTspRequest().build()));
    }

    @Test
    void rejectsDisabledTspProfile_withBadRequest() {
        SigningProfile signingProfile = createTimestampingSigningProfile("sp-for-disabled-tsp", true);
        createTspProfileFor("disabled-tsp", false, signingProfile);

        TspException ex = Assertions.assertThrows(TspException.class,
                () -> tsaService.processTspRequestForTspProfile("disabled-tsp", aTspRequest().build()));
        Assertions.assertEquals(TspFailureInfo.BAD_REQUEST, ex.getFailureInfo());
        Assertions.assertTrue(ex.getClientMessage().contains("not enabled"));
    }

    @Test
    void rejectsDisabledSigningProfile_withBadRequest() {
        SigningProfile disabledSigningProfile = createTimestampingSigningProfile("disabled-sp", false);
        createTspProfileFor("tsp-with-disabled-sp", true, disabledSigningProfile);

        TspException ex = Assertions.assertThrows(TspException.class,
                () -> tsaService.processTspRequestForTspProfile("tsp-with-disabled-sp", aTspRequest().build()));
        Assertions.assertEquals(TspFailureInfo.BAD_REQUEST, ex.getFailureInfo());
        Assertions.assertTrue(ex.getClientMessage().contains("not enabled"));
    }

    @Test
    void succeeds_whenBothProfilesEnabled_andAuthorized() throws Exception {
        SigningProfile signingProfile = createTimestampingSigningProfile("sp-ok", true);
        createTspProfileFor("tsp-ok", true, signingProfile);

        TspResponse response = tsaService.processTspRequestForTspProfile("tsp-ok", aTspRequest().build());

        Assertions.assertTrue(response instanceof TspResponse.Granted);
        verify(managedTimestampEngine).process(any(), any());
    }

    // ── Indirect signing-profile route (/signingProfiles/{name}/sign) ─────────

    @Test
    void indirectRoute_authorizesAgainstLinkedTspProfileUuid_andTspSignAction() throws Exception {
        SigningProfile signingProfile = createTimestampingSigningProfile("sp-indirect-authz", true);
        TspProfile linkedTspProfile = createTspProfileFor("tsp-indirect-authz", true, signingProfile);
        linkTspProfile(signingProfile, linkedTspProfile);

        tsaService.processTspRequestForSigningProfile("sp-indirect-authz", aTspRequest().build());

        verify(opaClient, atLeastOnce()).checkResourceAccess(
                any(),
                org.mockito.ArgumentMatchers.argThat(req -> isTspSignFor(req, linkedTspProfile.getUuid())),
                any(), any());
        verify(managedTimestampEngine).process(any(), any());
    }

    @Test
    void indirectRoute_deniesObjectLevel_whenNotAuthorizedForLinkedTspProfile() {
        SigningProfile signingProfile = createTimestampingSigningProfile("sp-indirect-denied", true);
        TspProfile linkedTspProfile = createTspProfileFor("tsp-indirect-denied", true, signingProfile);
        linkTspProfile(signingProfile, linkedTspProfile);

        denyTspSignForObject(linkedTspProfile.getUuid());

        Assertions.assertThrows(AccessDeniedException.class,
                () -> tsaService.processTspRequestForSigningProfile("sp-indirect-denied", aTspRequest().build()));
    }

    @Test
    void indirectRoute_rejectsDisabledLinkedTspProfile_withBadRequest() {
        SigningProfile signingProfile = createTimestampingSigningProfile("sp-indirect-disabled-tsp", true);
        TspProfile linkedTspProfile = createTspProfileFor("tsp-indirect-disabled", false, signingProfile);
        linkTspProfile(signingProfile, linkedTspProfile);

        TspException ex = Assertions.assertThrows(TspException.class,
                () -> tsaService.processTspRequestForSigningProfile("sp-indirect-disabled-tsp", aTspRequest().build()));
        Assertions.assertEquals(TspFailureInfo.BAD_REQUEST, ex.getFailureInfo());
        Assertions.assertTrue(ex.getClientMessage().contains("not enabled"));
    }

    @Test
    void indirectRoute_rejectsSigningProfileWithNoLinkedTspProfile_withBadRequest() {
        createTimestampingSigningProfile("sp-indirect-unlinked", true);

        TspException ex = Assertions.assertThrows(TspException.class,
                () -> tsaService.processTspRequestForSigningProfile("sp-indirect-unlinked", aTspRequest().build()));
        Assertions.assertEquals(TspFailureInfo.BAD_REQUEST, ex.getFailureInfo());
    }
}
