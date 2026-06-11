package com.otilm.core.service;

import com.otilm.api.exception.NotFoundException;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.client.signing.protocols.tsp.TspBasicCredentialDto;
import com.otilm.api.model.client.signing.protocols.tsp.TspBasicCredentialRequestDto;
import com.otilm.api.model.core.auth.UserDetailDto;
import com.otilm.api.model.core.secret.SecretDetailDto;
import com.otilm.core.dao.entity.VaultInstance;
import com.otilm.core.dao.entity.VaultProfile;
import com.otilm.core.dao.entity.signing.TspProfile;
import com.otilm.core.dao.repository.VaultInstanceRepository;
import com.otilm.core.dao.repository.VaultProfileRepository;
import com.otilm.core.dao.repository.signing.TspProfileRepository;
import com.otilm.core.security.authn.client.CredentialVerificationCache;
import com.otilm.core.security.authz.SecuredParentUUID;
import com.otilm.core.security.authz.SecuredUUID;
import com.otilm.core.util.BaseSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TspProfileBasicCredentialServiceImplTest extends BaseSpringBootTest {

    @Autowired
    private TspProfileBasicCredentialService service;

    @Autowired
    private TspProfileRepository tspProfileRepository;
    @Autowired
    private VaultProfileRepository vaultProfileRepository;
    @Autowired
    private VaultInstanceRepository vaultInstanceRepository;

    @MockitoBean
    private SecretService secretService;
    @MockitoBean
    private CredentialVerificationCache credentialVerificationCache;
    @MockitoBean
    private UserManagementService userManagementService;

    private TspProfile profileWithVault;
    private TspProfile profileNoVault;
    private UUID mappedUserUuid;

    @BeforeEach
    void createProfilesAndStubCollaborators() throws Exception {
        VaultInstance vaultInstance = new VaultInstance();
        vaultInstance.setName("testInstance");
        vaultInstanceRepository.save(vaultInstance);

        VaultProfile vaultProfile = new VaultProfile();
        vaultProfile.setName("testVaultProfile");
        vaultProfile.setVaultInstance(vaultInstance);
        vaultProfile.setVaultInstanceUuid(vaultInstance.getUuid());
        vaultProfileRepository.save(vaultProfile);

        profileWithVault = new TspProfile();
        profileWithVault.setName("tsp-with-vault");
        profileWithVault.setVaultProfileUuid(vaultProfile.getUuid());
        profileWithVault = tspProfileRepository.save(profileWithVault);

        profileNoVault = new TspProfile();
        profileNoVault.setName("tsp-no-vault");
        profileNoVault = tspProfileRepository.save(profileNoVault);

        mappedUserUuid = UUID.randomUUID();

        UserDetailDto user = new UserDetailDto();
        user.setUuid(mappedUserUuid.toString());
        user.setUsername("mapped-user");
        when(userManagementService.getUser(anyString())).thenReturn(user);

        when(secretService.createSecret(any(), any(), any()))
                .thenReturn(secretDtoWithUuid(UUID.randomUUID()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static SecretDetailDto secretDtoWithUuid(UUID uuid) {
        SecretDetailDto dto = new SecretDetailDto();
        dto.setUuid(uuid.toString());
        return dto;
    }

    private TspBasicCredentialRequestDto request(String username, String password) {
        TspBasicCredentialRequestDto request = new TspBasicCredentialRequestDto();
        request.setUsername(username);
        request.setPassword(password);
        request.setMappedUserUuid(mappedUserUuid);
        return request;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        void throwsValidation_whenProfileHasNoVault() {
            // given — profileNoVault has no vault profile configured

            // when / then
            assertThatThrownBy(() -> service.create(SecuredParentUUID.fromUUID(profileNoVault.getUuid()), request("svc", "secret")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void persistsAndIsListable() throws Exception {
            // given
            SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

            // when
            TspBasicCredentialDto created = service.create(parent, request("svc-account", "secret"));

            // then
            assertThat(created).isNotNull();
            assertThat(created.getUuid()).isNotNull();
            assertThat(created.getUsername()).isEqualTo("svc-account");
            assertThat(created.getMappedUser()).isNotNull();
            assertThat(created.getMappedUser().getUuid()).isEqualTo(mappedUserUuid.toString());

            verify(secretService, times(1)).createSecret(any(), any(), any());

            List<TspBasicCredentialDto> listed = service.list(parent);
            assertThat(listed).hasSize(1);
            assertThat(listed.getFirst().getUuid()).isEqualTo(created.getUuid());
            assertThat(listed.getFirst().getUsername()).isEqualTo("svc-account");
        }

        @Test
        void cleansUpVaultSecret_whenDuplicateUsername() throws Exception {
            // given
            SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

            UUID secretUuidA = UUID.randomUUID();
            UUID secretUuidB = UUID.randomUUID();
            when(secretService.createSecret(any(), any(), any()))
                    .thenReturn(secretDtoWithUuid(secretUuidA))
                    .thenReturn(secretDtoWithUuid(secretUuidB));

            service.create(parent, request("dup", "secret"));

            // when / then — the duplicate is rejected
            assertThatThrownBy(() -> service.create(parent, request("dup", "secret2")))
                    .isInstanceOf(ValidationException.class);

            // then — best-effort cleanup of the orphaned second vault secret
            verify(secretService, times(1)).deleteSecret(eq(secretUuidB), eq(true));
            assertThat(service.list(parent)).hasSize(1);
        }
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Nested
    class Update {

        @Test
        void rotatesAndEvicts_whenPasswordProvided() throws Exception {
            // given
            SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

            UUID secretUuid = UUID.randomUUID();
            when(secretService.createSecret(any(), any(), any())).thenReturn(secretDtoWithUuid(secretUuid));
            TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
            SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

            // when
            service.update(parent, credentialUuid, request("svc-renamed", "newsecret"));

            // then
            verify(secretService, times(1)).updateSecret(eq(secretUuid), any());
            verify(credentialVerificationCache, times(1)).evictBySecretUuid(secretUuid);
        }

        @Test
        void doesNotRotateOrEvict_whenPasswordAbsent() throws Exception {
            // given
            SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

            UUID secretUuid = UUID.randomUUID();
            when(secretService.createSecret(any(), any(), any())).thenReturn(secretDtoWithUuid(secretUuid));
            TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
            SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

            // when — null password, then blank password
            service.update(parent, credentialUuid, request("svc-renamed", null));
            service.update(parent, credentialUuid, request("svc-renamed-2", "  "));

            // then
            verify(secretService, never()).updateSecret(any(), any());
            verify(credentialVerificationCache, never()).evictBySecretUuid(any());
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Nested
    class Delete {

        @Test
        void deletesSecretRemovesRowAndEvicts() throws Exception {
            // given
            SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

            UUID secretUuid = UUID.randomUUID();
            when(secretService.createSecret(any(), any(), any())).thenReturn(secretDtoWithUuid(secretUuid));
            TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
            SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

            // when
            service.delete(parent, credentialUuid);

            // then
            verify(secretService, times(1)).deleteSecret(eq(secretUuid), eq(true));
            verify(credentialVerificationCache, times(1)).evictBySecretUuid(secretUuid);
            assertThat(service.list(parent)).isEmpty();
        }
    }

    // ── GetAndList ────────────────────────────────────────────────────────────

    @Nested
    class GetAndList {

        @Test
        void scopesToParent() throws NotFoundException {
            // given
            SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());
            TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
            SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());
            SecuredParentUUID otherParent = SecuredParentUUID.fromUUID(profileNoVault.getUuid());

            // when / then — not reachable through a different parent
            assertThatThrownBy(() -> service.get(otherParent, credentialUuid)).isInstanceOf(NotFoundException.class);
            assertThat(service.list(otherParent)).isEmpty();

            // then — reachable through its own parent
            assertThat(service.get(parent, credentialUuid).getUuid()).isEqualTo(created.getUuid());
        }
    }
}
