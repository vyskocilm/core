package com.otilm.core.signing.tsa.impl;

import com.otilm.api.exception.NotFoundException;
import com.otilm.api.interfaces.core.tsp.error.TspException;
import com.otilm.api.interfaces.core.tsp.error.TspFailureInfo;
import com.otilm.api.model.core.auth.Resource;
import com.otilm.core.model.auth.ResourceAction;
import com.otilm.core.model.signing.SigningProfileModel;
import com.otilm.core.model.signing.TspProfileModel;
import com.otilm.core.model.signing.resolved.ResolvedManagedTimestampingProfile;
import com.otilm.core.model.signing.workflow.ManagedTimestampingWorkflow;
import com.otilm.core.model.signing.workflow.SigningWorkflow;
import com.otilm.core.security.authz.ExternalAuthorization;
import com.otilm.core.security.authz.SecuredUUID;
import com.otilm.core.service.SigningProfileService;
import com.otilm.core.service.TspProfileService;
import com.otilm.core.signing.tsa.ManagedTimestampEngine;
import com.otilm.core.signing.tsa.TsaService;
import com.otilm.core.signing.tsa.resolver.SigningProfileResolverFactory;
import com.otilm.core.signing.tsa.messages.TspRequest;
import com.otilm.core.signing.tsa.messages.TspResponse;
import com.otilm.core.signing.tsa.validator.TspRequestValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class TsaServiceImpl implements TsaService {

    private final TspRequestValidator tspRequestValidator;
    private final TspProfileService tspProfileService;
    private final SigningProfileService signingProfileService;
    private final SigningProfileResolverFactory signingProfileResolverFactory;
    private final ManagedTimestampEngine managedTimestampEngine;

    private TsaServiceImpl self;

    public TsaServiceImpl(TspRequestValidator tspRequestValidator, SigningProfileService signingProfileService, SigningProfileResolverFactory signingProfileResolverFactory, TspProfileService tspProfileService, ManagedTimestampEngine managedTimestampEngine) {
        this.tspRequestValidator = tspRequestValidator;
        this.signingProfileService = signingProfileService;
        this.signingProfileResolverFactory = signingProfileResolverFactory;
        this.tspProfileService = tspProfileService;
        this.managedTimestampEngine = managedTimestampEngine;
    }

    @Lazy
    @Autowired
    public void setSelf(TsaServiceImpl self) {
        this.self = self;
    }

    @Override
    public TspResponse processTspRequestForTspProfile(String tspProfileName, TspRequest request) throws NotFoundException, TspException {
        TspProfileModel tspProfile = tspProfileService.resolveTspProfileForAuthentication(tspProfileName);
        return self.authorizeAndProcessForTspProfile(SecuredUUID.fromUUID(tspProfile.uuid()), tspProfile, request);
    }

    @ExternalAuthorization(resource = Resource.TSP_PROFILE, action = ResourceAction.TSP_SIGN)
    public TspResponse authorizeAndProcessForTspProfile(SecuredUUID tspProfileUuid, TspProfileModel tspProfile, TspRequest request)
            throws NotFoundException, TspException {
        if (!tspProfile.enabled()) {
            throw new TspException(TspFailureInfo.BAD_REQUEST, "TSP Profile is not enabled", "TSP Profile is not enabled");
        }
        return processSigningProfileInternal(tspProfile.defaultSigningProfileName(), tspProfile, false, request);
    }

    @Override
    public TspResponse processTspRequestForSigningProfile(String signingProfileName, TspRequest request) throws NotFoundException, TspException {
        TspProfileModel tspProfile = signingProfileService.resolveTspProfileForSigningProfileAuthentication(signingProfileName)
                .orElseThrow(() -> new TspException(TspFailureInfo.BAD_REQUEST,
                        "Signing Profile '%s' has no linked TSP Profile".formatted(signingProfileName),
                        "Signing Profile is not available for timestamping"));
        return self.authorizeAndProcessForSigningProfile(SecuredUUID.fromUUID(tspProfile.uuid()), tspProfile, signingProfileName, request);
    }

    @ExternalAuthorization(resource = Resource.TSP_PROFILE, action = ResourceAction.TSP_SIGN)
    public TspResponse authorizeAndProcessForSigningProfile(SecuredUUID tspProfileUuid, TspProfileModel tspProfile, String signingProfileName, TspRequest request)
            throws NotFoundException, TspException {
        if (!tspProfile.enabled()) {
            throw new TspException(TspFailureInfo.BAD_REQUEST, "TSP Profile is not enabled", "TSP Profile is not enabled");
        }
        return processSigningProfileInternal(signingProfileName, tspProfile, true, request);
    }

    private TspResponse processSigningProfileInternal(String signingProfileName, TspProfileModel tspProfile, boolean assertLinkage, TspRequest request) throws NotFoundException, TspException {
        SigningProfileModel<?, ?> signingProfile = signingProfileService.getSigningProfileModel(signingProfileName);
        if (assertLinkage) {
            assertLinkedToTspProfile(signingProfile, tspProfile);
        }

        if (!signingProfile.enabled()) {
            throw new TspException(TspFailureInfo.BAD_REQUEST, "Signing Profile is not enabled", "Signing Profile is not enabled");
        }

        SigningWorkflow workflow = signingProfile.workflow();
        if (!(workflow instanceof ManagedTimestampingWorkflow timestampingWorkflow)) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signing Profile '%s' is not a managed timestamping profile (workflow: %s)".formatted(
                            signingProfileName, workflow.getClass().getSimpleName()),
                    "The system is misconfigured.");
        }
        tspRequestValidator.validate(timestampingWorkflow, request);

        ResolvedManagedTimestampingProfile resolvedProfile = signingProfileResolverFactory.resolve(signingProfile);
        return managedTimestampEngine.process(request, resolvedProfile);
    }

    /**
     * Asserts the client-chosen Signing Profile (indirect route) is actually linked to the TSP Profile we authenticated against.
     * The indirect resolution derives the TSP Profile from the Signing Profile's link, so this guards against the two cache reads
     * diverging (cache skew).
     */
    static void assertLinkedToTspProfile(SigningProfileModel<?, ?> signingProfile, TspProfileModel tspProfile) throws TspException {
        if (!tspProfile.uuid().equals(signingProfile.tspProfileUuid())) {
            throw new TspException(TspFailureInfo.BAD_REQUEST,
                    "Signing Profile '%s' (tspProfileUuid=%s) is not linked to TSP Profile '%s' (uuid=%s)".formatted(
                            signingProfile.name(), signingProfile.tspProfileUuid(), tspProfile.name(), tspProfile.uuid()),
                    "Signing Profile is not available for timestamping");
        }
    }
}
