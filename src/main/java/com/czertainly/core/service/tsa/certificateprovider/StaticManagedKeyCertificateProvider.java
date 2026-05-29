package com.czertainly.core.service.tsa.certificateprovider;

import com.czertainly.api.interfaces.core.tsp.error.TspException;
import com.czertainly.api.interfaces.core.tsp.error.TspFailureInfo;
import com.czertainly.api.model.client.signing.profile.workflow.SigningWorkflowType;
import com.czertainly.core.model.signing.resolved.ResolvedManagedScheme;
import com.czertainly.core.model.signing.resolved.ResolvedStaticKeyManagedSigning;
import com.czertainly.core.service.tsa.CertificateChain;
import com.czertainly.core.util.CertificateUtil;
import org.springframework.stereotype.Component;

@Component
public class StaticManagedKeyCertificateProvider implements CertificateProvider {

    @Override
    public boolean supports(ResolvedManagedScheme signingScheme) {
        return signingScheme instanceof ResolvedStaticKeyManagedSigning;
    }

    @Override
    public ValidationResult validate(ResolvedManagedScheme signingScheme, boolean qualifiedTimestamp) {
        if (!(signingScheme instanceof ResolvedStaticKeyManagedSigning signingSchemeModel)) {
            return ValidationResult.nok(TspFailureInfo.SYSTEM_FAILURE,
                    "The signing scheme '%s' is not supported by 'StaticManagedKeyCertificateProvider'.".formatted(signingScheme.getClass().getSimpleName()),
                    "The system is misconfigured.");
        }
        if (!CertificateUtil.isCertificateDigitalSigningAcceptable(signingSchemeModel.certificate(), SigningWorkflowType.TIMESTAMPING, qualifiedTimestamp)) {
            return ValidationResult.nok(TspFailureInfo.SYSTEM_FAILURE,
                    "Signer certificate is not acceptable for %s timestamping".formatted(qualifiedTimestamp ? "qualified" : "non-qualified"),
                    "Signer certificate failed validation.");
        }
        return ValidationResult.ok();
    }

    @Override
    public CertificateChain getCertificateChain(ResolvedManagedScheme signingScheme) throws TspException {
        if (!(signingScheme instanceof ResolvedStaticKeyManagedSigning signingSchemeModel)) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    String.format("The signing scheme '%s' is not supported by 'StaticManagedKeyCertificateProvider'.", signingScheme.getClass().getSimpleName()),
                    "The system is misconfigured.");
        }

        if (signingSchemeModel.chain().isEmpty()) {
            throw new TspException(TspFailureInfo.SYSTEM_FAILURE,
                    "Signing certificate or its chain is not available for UUID %s.".formatted(signingSchemeModel.certificate().getUuid()),
                    "Signing key certificate could not be found.");
        }
        return CertificateChain.of(signingSchemeModel.chain());
    }
}
