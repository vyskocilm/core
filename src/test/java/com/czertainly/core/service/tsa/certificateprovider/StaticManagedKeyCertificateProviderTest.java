package com.czertainly.core.service.tsa.certificateprovider;

import com.czertainly.api.interfaces.core.tsp.error.TspException;
import com.czertainly.api.interfaces.core.tsp.error.TspFailureInfo;
import com.czertainly.api.model.core.certificate.CertificateState;
import com.czertainly.core.dao.entity.CertificateBuilder;
import com.czertainly.core.model.signing.resolved.ResolvedStaticKeyManagedSigning;
import com.czertainly.core.service.tsa.CertificateChain;
import com.czertainly.core.util.CertificateTestUtil;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaticManagedKeyCertificateProviderTest {

    private final StaticManagedKeyCertificateProvider provider = new StaticManagedKeyCertificateProvider();

    // ── validate() ───────────────────────────────────────────────────────────

    @Test
    void validate_returnsNok_whenCertificateIsNotAcceptableForNonQualifiedTimestamping() {
        // given — a revoked certificate is not acceptable for signing
        var certificate = CertificateBuilder.aCertificate().state(CertificateState.REVOKED).build();
        var scheme = new ResolvedStaticKeyManagedSigning(certificate, List.of(), List.of());

        // when
        var result = provider.validate(scheme, false);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Nok.class);
        assertThat(((ValidationResult.Nok) result).failureInfo()).isEqualTo(TspFailureInfo.SYSTEM_FAILURE);
    }

    @Test
    void validate_returnsOk_whenCertificateIsAcceptableForNonQualifiedTimestamping() {
        // given
        var scheme = new ResolvedStaticKeyManagedSigning(CertificateBuilder.valid(), List.of(), List.of());

        // when
        var result = provider.validate(scheme, false);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Ok.class);
    }

    @Test
    void validate_returnsNok_whenCertificateHasNoQcComplianceForQualifiedTimestamping() {
        // given — qcCompliance is absent, which is required for qualified timestamps (ETSI EN 319 421)
        var scheme = new ResolvedStaticKeyManagedSigning(CertificateBuilder.valid(), List.of(), List.of());

        // when
        var result = provider.validate(scheme, true);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Nok.class);
        assertThat(((ValidationResult.Nok) result).failureInfo()).isEqualTo(TspFailureInfo.SYSTEM_FAILURE);
    }

    @Test
    void validate_returnsOk_whenCertificateIsAcceptableForQualifiedTimestamping() {
        // given
        var certificate = CertificateBuilder.aCertificate().qcCompliance(true).build();
        var scheme = new ResolvedStaticKeyManagedSigning(certificate, List.of(), List.of());

        // when
        var result = provider.validate(scheme, true);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Ok.class);
    }

    // ── getCertificateChain() ─────────────────────────────────────────────────

    @Test
    void getCertificateChain_throwsTspException_whenChainIsEmpty() {
        // given — the resolved scheme carries no certificate chain
        var certificate = CertificateBuilder.aCertificate().withoutKey().build();
        var scheme = new ResolvedStaticKeyManagedSigning(certificate, List.of(), List.of());

        // when / then
        var exception = assertThrows(TspException.class, () -> provider.getCertificateChain(scheme));
        assertThat(exception.getFailureInfo()).isEqualTo(TspFailureInfo.SYSTEM_FAILURE);
    }

    @Test
    void getCertificateChain_returnsCertificateChain_whenChainIsPresent() throws Exception {
        // given
        X509Certificate x509 = CertificateTestUtil.createTimestampingCertificate();
        var certificate = CertificateBuilder.aCertificate().withoutKey().build();
        var scheme = new ResolvedStaticKeyManagedSigning(certificate, List.of(x509), List.of());

        // when
        CertificateChain result = provider.getCertificateChain(scheme);

        // then
        assertThat(result.signingCertificate()).isEqualTo(x509);
        assertThat(result.chain()).isEqualTo(List.of(x509));
    }
}
