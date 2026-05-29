package com.czertainly.core.service.tsa.certificateprovider;

import com.czertainly.api.interfaces.core.tsp.error.TspException;
import com.czertainly.api.interfaces.core.tsp.error.TspFailureInfo;
import com.czertainly.core.model.signing.resolved.ResolvedManagedScheme;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Selects the appropriate {@link CertificateProvider} for a given signing scheme.
 */
@Component
public class CertificateProviderFactory {

    private final List<CertificateProvider> providers;

    public CertificateProviderFactory(List<CertificateProvider> providers) {
        this.providers = providers;
    }

    public CertificateProvider getProvider(ResolvedManagedScheme signingScheme) throws TspException {
        return providers.stream()
                .filter(p -> p.supports(signingScheme))
                .findFirst()
                .orElseThrow(() -> new TspException(
                        TspFailureInfo.SYSTEM_FAILURE,
                        "No CertificateProvider supports signing scheme '%s'".formatted(
                                signingScheme.getClass().getSimpleName()),
                        "The system is misconfigured."));
    }
}