package com.czertainly.core.service.tsa.certificateprovider;

import com.czertainly.api.interfaces.core.tsp.error.TspException;
import com.czertainly.api.interfaces.core.tsp.error.TspFailureInfo;
import com.czertainly.api.model.common.enums.cryptography.KeyAlgorithm;
import com.czertainly.api.model.core.certificate.CertificateState;
import com.czertainly.core.model.crypto.CryptographicKeyItemModel;
import com.czertainly.core.model.crypto.CryptographicKeyItemModelFixtures;
import com.czertainly.core.model.signing.SigningCertificateBuilder;
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

    private static final List<CryptographicKeyItemModel> SIGNING_KEY_ITEMS = List.of(
            CryptographicKeyItemModelFixtures.activeSigningPrivateKey(KeyAlgorithm.RSA),
            CryptographicKeyItemModelFixtures.publicKey(KeyAlgorithm.RSA));

    // ── validate() ───────────────────────────────────────────────────────────

    @Test
    void validate_returnsNok_whenCertificateIsNotAcceptableForNonQualifiedTimestamping() {
        // given — a revoked certificate is not acceptable for signing
        var certificate = SigningCertificateBuilder.aSigningCertificate().state(CertificateState.REVOKED).build();
        var scheme = new ResolvedStaticKeyManagedSigning(certificate, SIGNING_KEY_ITEMS, List.of(), List.of());

        // when
        var result = provider.validate(scheme, false);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Nok.class);
        assertThat(((ValidationResult.Nok) result).failureInfo()).isEqualTo(TspFailureInfo.SYSTEM_FAILURE);
    }

    @Test
    void validate_returnsOk_whenCertificateIsAcceptableForNonQualifiedTimestamping() {
        // given
        var scheme = new ResolvedStaticKeyManagedSigning(SigningCertificateBuilder.valid(), SIGNING_KEY_ITEMS, List.of(), List.of());

        // when
        var result = provider.validate(scheme, false);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Ok.class);
    }

    @Test
    void validate_returnsNok_whenCertificateHasNoQcComplianceForQualifiedTimestamping() {
        // given — qcCompliance is absent, which is required for qualified timestamps (ETSI EN 319 421)
        var scheme = new ResolvedStaticKeyManagedSigning(SigningCertificateBuilder.valid(), SIGNING_KEY_ITEMS, List.of(), List.of());

        // when
        var result = provider.validate(scheme, true);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Nok.class);
        assertThat(((ValidationResult.Nok) result).failureInfo()).isEqualTo(TspFailureInfo.SYSTEM_FAILURE);
    }

    @Test
    void validate_returnsOk_whenCertificateIsAcceptableForQualifiedTimestamping() {
        // given
        var certificate = SigningCertificateBuilder.aSigningCertificate().qcCompliance(true).build();
        var scheme = new ResolvedStaticKeyManagedSigning(certificate, SIGNING_KEY_ITEMS, List.of(), List.of());

        // when
        var result = provider.validate(scheme, true);

        // then
        assertThat(result).isInstanceOf(ValidationResult.Ok.class);
    }

    // ── getCertificateChain() ─────────────────────────────────────────────────

    @Test
    void getCertificateChain_throwsTspException_whenChainIsEmpty() {
        // given — the resolved scheme carries no certificate chain
        var scheme = new ResolvedStaticKeyManagedSigning(SigningCertificateBuilder.valid(), List.of(), List.of(), List.of());

        // when / then
        var exception = assertThrows(TspException.class, () -> provider.getCertificateChain(scheme));
        assertThat(exception.getFailureInfo()).isEqualTo(TspFailureInfo.SYSTEM_FAILURE);
    }

    @Test
    void getCertificateChain_returnsCertificateChain_whenChainIsPresent() throws Exception {
        // given
        X509Certificate x509 = CertificateTestUtil.createTimestampingCertificate();
        var scheme = new ResolvedStaticKeyManagedSigning(SigningCertificateBuilder.valid(), List.of(), List.of(x509), List.of());

        // when
        CertificateChain result = provider.getCertificateChain(scheme);

        // then
        assertThat(result.signingCertificate()).isEqualTo(x509);
        assertThat(result.chain()).isEqualTo(List.of(x509));
    }
}
