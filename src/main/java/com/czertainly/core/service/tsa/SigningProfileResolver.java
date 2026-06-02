package com.czertainly.core.service.tsa;

import com.czertainly.api.clients.ApiClientConnectorInfo;
import com.czertainly.api.exception.NotFoundException;
import com.czertainly.api.interfaces.core.tsp.error.TspException;
import com.czertainly.api.interfaces.core.tsp.error.TspFailureInfo;
import com.czertainly.core.model.crypto.CryptographicKeyItemModel;
import com.czertainly.core.model.signing.SigningCertificate;
import com.czertainly.core.model.signing.SigningProfileModel;
import com.czertainly.core.model.signing.resolved.ResolvedManagedScheme;
import com.czertainly.core.model.signing.resolved.ResolvedManagedTimestampingProfile;
import com.czertainly.core.model.signing.resolved.ResolvedStaticKeyManagedSigning;
import com.czertainly.core.model.signing.scheme.SigningSchemeModel;
import com.czertainly.core.model.signing.scheme.StaticKeyManagedSigning;
import com.czertainly.core.model.signing.timequality.LocalClockTimeQualityConfiguration;
import com.czertainly.core.model.signing.timequality.TimeQualityConfigurationModel;
import com.czertainly.core.model.signing.workflow.ManagedTimestampingWorkflow;
import com.czertainly.core.model.signing.workflow.SigningWorkflow;
import com.czertainly.core.service.CertificateService;
import com.czertainly.core.service.CryptographicKeyService;
import com.czertainly.core.service.TimeQualityConfigurationService;
import com.czertainly.core.service.v2.ConnectorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves a cached, UUID-only {@link SigningProfileModel} into the transient
 * {@link ResolvedManagedTimestampingProfile} consumed by the timestamping pipeline.
 *
 * <p>The cached model deliberately holds only UUIDs for objects owned by other caches or
 * repositories (Time Quality Configuration, Signature Formatter Connector, signing certificate).
 * This resolver dereferences those UUIDs at request time. The resolved form is never cached.</p>
 *
 * <p>Only managed timestamping with a static signing key is supported today; other workflow or
 * scheme variants are rejected with a {@link TspException}.</p>
 */
@Component
public class SigningProfileResolver {

    private CertificateService certificateService;
    private CryptographicKeyService cryptographicKeyService;
    private TimeQualityConfigurationService timeQualityConfigurationService;
    private ConnectorService connectorService;

    public ResolvedManagedTimestampingProfile resolve(SigningProfileModel<?, ?> model) throws TspException {
        SigningWorkflow workflow = model.workflow();
        if (!(workflow instanceof ManagedTimestampingWorkflow timestampingWorkflow)) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signing Profile '%s' is not a managed timestamping profile (workflow: %s)".formatted(
                            model.name(), workflow.getClass().getSimpleName()),
                    "The system is misconfigured.");
        }

        ResolvedManagedScheme resolvedScheme = resolveScheme(model.name(), model.signingScheme());
        TimeQualityConfigurationModel timeQualityConfiguration =
                resolveTimeQualityConfiguration(timestampingWorkflow.timeQualityConfigurationUuid());
        ApiClientConnectorInfo signatureFormatterConnector =
                resolveSignatureFormatterConnector(timestampingWorkflow.signatureFormatterConnectorUuid());

        return new ResolvedManagedTimestampingProfile(
                model.uuid(),
                model.name(),
                model.description(),
                model.version(),
                model.enabled(),
                model.enabledProtocols(),
                timestampingWorkflow.isQualifiedTimestamp(),
                timestampingWorkflow.defaultPolicyId(),
                timestampingWorkflow.allowedPolicyIds(),
                timestampingWorkflow.allowedDigestAlgorithms(),
                timestampingWorkflow.validateTokenSignature(),
                timestampingWorkflow.signatureFormatterConnectorAttributes(),
                timeQualityConfiguration,
                signatureFormatterConnector,
                resolvedScheme);
    }

    private ResolvedManagedScheme resolveScheme(String profileName, SigningSchemeModel scheme) throws TspException {
        if (!(scheme instanceof StaticKeyManagedSigning staticKey)) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signing Profile '%s' uses an unsupported signing scheme: %s".formatted(
                            profileName, scheme.getClass().getSimpleName()),
                    "The system is misconfigured.");
        }

        UUID certificateUuid = staticKey.certificateUuid();
        SigningCertificate certificate;
        List<CryptographicKeyItemModel> keyItems = new ArrayList<>();
        try {
            certificate = certificateService.getSigningCertificate(certificateUuid);
            for (UUID keyItemUuid : certificate.keyItemUuids()) {
                keyItems.add(cryptographicKeyService.getKeyItemModel(keyItemUuid));
            }
        } catch (NotFoundException e) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signing certificate not found: " + certificateUuid, e,
                    "Signing key certificate could not be found.");
        }

        List<X509Certificate> chain;
        try {
            chain = certificateService.getCertificateChainForSigning(certificateUuid, true);
        } catch (CertificateException e) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Failed to decode certificate chain for %s. %s".formatted(certificateUuid, e.getLocalizedMessage()),
                    "Certificate chain could not be parsed.");
        }
        if (chain.isEmpty()) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signing certificate or its chain is not available for UUID %s.".formatted(certificateUuid),
                    "Signing key certificate could not be found.");
        }

        return new ResolvedStaticKeyManagedSigning(certificate, List.copyOf(keyItems), chain, staticKey.signingOperationAttributes());
    }

    private TimeQualityConfigurationModel resolveTimeQualityConfiguration(UUID timeQualityConfigurationUuid) throws TspException {
        if (timeQualityConfigurationUuid == null) {
            // No explicit Time Quality Configuration: fall back to the local system clock.
            return LocalClockTimeQualityConfiguration.INSTANCE;
        }
        try {
            return timeQualityConfigurationService.getTimeQualityConfigurationModel(timeQualityConfigurationUuid);
        } catch (NotFoundException e) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Time Quality Configuration not found: " + timeQualityConfigurationUuid, e,
                    "Internal error: signing configuration is invalid");
        }
    }

    private ApiClientConnectorInfo resolveSignatureFormatterConnector(UUID signatureFormatterConnectorUuid) throws TspException {
        try {
            return connectorService.getConnectorForApiClient(signatureFormatterConnectorUuid);
        } catch (NotFoundException e) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signature formatter connector not found: " + signatureFormatterConnectorUuid, e,
                    "Internal error: signing configuration is invalid");
        }
    }

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @Autowired
    public void setCryptographicKeyService(CryptographicKeyService cryptographicKeyService) {
        this.cryptographicKeyService = cryptographicKeyService;
    }

    @Autowired
    public void setTimeQualityConfigurationService(TimeQualityConfigurationService timeQualityConfigurationService) {
        this.timeQualityConfigurationService = timeQualityConfigurationService;
    }

    @Autowired
    public void setConnectorService(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }
}
