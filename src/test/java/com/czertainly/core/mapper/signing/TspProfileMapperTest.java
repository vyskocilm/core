package com.czertainly.core.mapper.signing;

import com.otilm.api.model.core.signing.TspAuthenticationMethod;
import com.czertainly.core.dao.entity.VaultInstance;
import com.czertainly.core.dao.entity.VaultProfile;
import com.czertainly.core.dao.entity.signing.TspProfile;
import com.czertainly.core.dao.entity.signing.TspProfileBasicCredential;
import com.czertainly.core.model.signing.TspProfileModel;
import com.otilm.api.model.client.attribute.ResponseAttribute;
import com.otilm.api.model.client.signing.protocols.tsp.TspProfileDto;
import com.otilm.api.model.core.vaultprofile.VaultProfileDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class TspProfileMapperTest {

    @Test
    void toModel_populatesFingerprintFromLookup() {
        UUID secretUuid = UUID.randomUUID();
        UUID mappedUser = UUID.randomUUID();

        TspProfile profile = new TspProfile();
        profile.setName("p1");
        TspProfileBasicCredential cred = new TspProfileBasicCredential();
        cred.setUsername("alice");
        cred.setSecretUuid(secretUuid);
        cred.setMappedUserUuid(mappedUser);
        profile.getBasicCredentials().add(cred);

        Map<UUID, String> fingerprints = Map.of(secretUuid, "deadbeef");

        TspProfileModel model = TspProfileMapper.toModel(profile, List.of(), fingerprints);

        TspProfileModel.BasicCredentialRef ref = model.basicCredentials().get(0);
        Assertions.assertEquals("alice", ref.username());
        Assertions.assertEquals(secretUuid, ref.secretUuid());
        Assertions.assertEquals(mappedUser, ref.mappedUserUuid());
        Assertions.assertEquals("deadbeef", ref.fingerprint());
    }

    @Test
    void toModel_copiesMethodsAndCredentialRefs() {
        TspProfile profile = new TspProfile();
        profile.setName("p");
        profile.setAllowedAuthenticationMethods(List.of(TspAuthenticationMethod.BASIC_PASSWORD));
        TspProfileBasicCredential cred = new TspProfileBasicCredential();
        cred.setUsername("svc");
        cred.setSecretUuid(UUID.randomUUID());
        cred.setMappedUserUuid(UUID.randomUUID());
        profile.getBasicCredentials().add(cred);

        TspProfileModel model = TspProfileMapper.toModel(profile, List.of(), Map.of());

        Assertions.assertEquals(List.of(TspAuthenticationMethod.BASIC_PASSWORD), model.allowedAuthenticationMethods());
        Assertions.assertEquals(1, model.basicCredentials().size());
        Assertions.assertEquals("svc", model.basicCredentials().get(0).username());
    }

    @Test
    void toModel_emptyProfile_yieldsEmptyLists() {
        TspProfile profile = new TspProfile();
        profile.setName("empty");
        // allowedAuthenticationMethods defaults to empty ArrayList; basicCredentials defaults to empty ArrayList

        TspProfileModel model = TspProfileMapper.toModel(profile, List.of(), Map.of());

        Assertions.assertNotNull(model.allowedAuthenticationMethods());
        Assertions.assertTrue(model.allowedAuthenticationMethods().isEmpty());
        Assertions.assertNotNull(model.basicCredentials());
        Assertions.assertTrue(model.basicCredentials().isEmpty());
    }

    @Test
    void toModel_multipleCredentials_mapsAllWithCorrectFields() {
        TspProfile profile = new TspProfile();
        profile.setName("multi");
        profile.setAllowedAuthenticationMethods(new ArrayList<>(List.of(TspAuthenticationMethod.BASIC_PASSWORD)));

        UUID secretA = UUID.randomUUID();
        UUID mappedA = UUID.randomUUID();
        TspProfileBasicCredential credA = new TspProfileBasicCredential();
        credA.setUsername("alice");
        credA.setSecretUuid(secretA);
        credA.setMappedUserUuid(mappedA);

        UUID secretB = UUID.randomUUID();
        UUID mappedB = UUID.randomUUID();
        TspProfileBasicCredential credB = new TspProfileBasicCredential();
        credB.setUsername("bob");
        credB.setSecretUuid(secretB);
        credB.setMappedUserUuid(mappedB);

        profile.getBasicCredentials().add(credA);
        profile.getBasicCredentials().add(credB);

        TspProfileModel model = TspProfileMapper.toModel(profile, List.of(), Map.of());

        Assertions.assertEquals(2, model.basicCredentials().size());

        TspProfileModel.BasicCredentialRef refA = model.basicCredentials().stream()
                .filter(r -> "alice".equals(r.username())).findFirst().orElseThrow();
        Assertions.assertEquals(secretA, refA.secretUuid());
        Assertions.assertEquals(mappedA, refA.mappedUserUuid());

        TspProfileModel.BasicCredentialRef refB = model.basicCredentials().stream()
                .filter(r -> "bob".equals(r.username())).findFirst().orElseThrow();
        Assertions.assertEquals(secretB, refB.secretUuid());
        Assertions.assertEquals(mappedB, refB.mappedUserUuid());
    }

    @Test
    void toDto_noVaultProfile_leavesNestedNull() {
        TspProfile profile = new TspProfile();
        profile.setUuid(UUID.randomUUID());
        profile.setName("no-vault");

        TspProfileDto dto = TspProfileMapper.toDto(profile, List.<ResponseAttribute>of());

        Assertions.assertNull(dto.getVaultProfile());
    }

    @Test
    void toDto_withVaultProfile_populatesNestedDto() {
        UUID vaultInstanceUuid = UUID.randomUUID();
        VaultInstance vaultInstance = new VaultInstance();
        vaultInstance.setUuid(vaultInstanceUuid);
        vaultInstance.setName("prod-vault");

        VaultProfile vaultProfile = new VaultProfile();
        vaultProfile.setUuid(UUID.randomUUID());
        vaultProfile.setName("basic-creds");
        vaultProfile.setDescription("creds for prod");
        vaultProfile.setEnabled(true);
        vaultProfile.setVaultInstance(vaultInstance); // also sets vaultInstanceUuid

        TspProfile profile = new TspProfile();
        profile.setUuid(UUID.randomUUID());
        profile.setName("with-vault");
        profile.setVaultProfile(vaultProfile);

        TspProfileDto dto = TspProfileMapper.toDto(profile, List.<ResponseAttribute>of());

        VaultProfileDto nested = dto.getVaultProfile();
        Assertions.assertNotNull(nested);
        Assertions.assertEquals(vaultProfile.getUuid().toString(), nested.getUuid());
        Assertions.assertEquals("basic-creds", nested.getName());
        Assertions.assertEquals("creds for prod", nested.getDescription());
        Assertions.assertTrue(nested.isEnabled());
        Assertions.assertNotNull(nested.getVaultInstance());
        Assertions.assertEquals(vaultInstanceUuid.toString(), nested.getVaultInstance().getUuid());
        Assertions.assertEquals("prod-vault", nested.getVaultInstance().getName());
    }
}
