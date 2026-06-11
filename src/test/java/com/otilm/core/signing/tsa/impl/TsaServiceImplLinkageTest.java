package com.otilm.core.signing.tsa.impl;

import com.otilm.api.interfaces.core.tsp.error.TspException;
import com.otilm.api.interfaces.core.tsp.error.TspFailureInfo;
import com.otilm.core.model.signing.SigningProfileModel;
import com.otilm.core.model.signing.SigningProfileModelBuilder;
import com.otilm.core.model.signing.TspProfileModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the indirect-route back-link assertion kernel. The mismatch branch is not reachable
 * through the live API (the indirect resolution derives the TSP Profile from the same link the Signing
 * Profile model carries), so it is exercised here as a pure function.
 */
class TsaServiceImplLinkageTest {

    private static TspProfileModel tspProfile(UUID uuid) {
        return new TspProfileModel(uuid, "tsp", null, true,  null, null, List.of(), List.of(), List.of(), null);
    }

    private static SigningProfileModel<?, ?> signingProfileLinkedTo(UUID tspProfileUuid) {
        return SigningProfileModelBuilder.aSigningProfile().name("sp").tspProfileUuid(tspProfileUuid).build();
    }

    @Test
    void passes_whenSigningProfileLinkedToSameTspProfile() {
        UUID tspUuid = UUID.randomUUID();

        assertThatCode(() -> TsaServiceImpl.assertLinkedToTspProfile(signingProfileLinkedTo(tspUuid), tspProfile(tspUuid)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_whenSigningProfileLinkedToDifferentTspProfile() {
        assertThatThrownBy(() -> TsaServiceImpl.assertLinkedToTspProfile(
                signingProfileLinkedTo(UUID.randomUUID()), tspProfile(UUID.randomUUID())))
                .isInstanceOf(TspException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((TspException) ex).getFailureInfo())
                        .isEqualTo(TspFailureInfo.BAD_REQUEST));
    }

    @Test
    void rejects_whenSigningProfileHasNoLink() {
        assertThatThrownBy(() -> TsaServiceImpl.assertLinkedToTspProfile(
                signingProfileLinkedTo(null), tspProfile(UUID.randomUUID())))
                .isInstanceOf(TspException.class);
    }
}
