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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

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
    void setUp() throws Exception {
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

    private SecretDetailDto secretDtoWithUuid(UUID uuid) {
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

    @Test
    void testCreate_RequiresVaultProfile() {
        Assertions.assertThrows(ValidationException.class,
                () -> service.create(SecuredParentUUID.fromUUID(profileNoVault.getUuid()), request("svc", "secret")));
    }

    @Test
    void testCreate_PersistsAndIsListable() throws Exception {
        SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

        TspBasicCredentialDto created = service.create(parent, request("svc-account", "secret"));

        Assertions.assertNotNull(created);
        Assertions.assertNotNull(created.getUuid());
        Assertions.assertEquals("svc-account", created.getUsername());
        Assertions.assertNotNull(created.getMappedUser());
        Assertions.assertEquals(mappedUserUuid.toString(), created.getMappedUser().getUuid());

        verify(secretService, times(1)).createSecret(any(), any(), any());

        List<TspBasicCredentialDto> listed = service.list(parent);
        Assertions.assertEquals(1, listed.size());
        Assertions.assertEquals(created.getUuid(), listed.getFirst().getUuid());
        Assertions.assertEquals("svc-account", listed.getFirst().getUsername());
    }

    @Test
    void testCreate_DuplicateUsername_CleansUpVaultSecret() throws Exception {
        SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

        UUID secretUuidA = UUID.randomUUID();
        UUID secretUuidB = UUID.randomUUID();
        when(secretService.createSecret(any(), any(), any()))
                .thenReturn(secretDtoWithUuid(secretUuidA))
                .thenReturn(secretDtoWithUuid(secretUuidB));

        service.create(parent, request("dup", "secret"));

        Assertions.assertThrows(ValidationException.class,
                () -> service.create(parent, request("dup", "secret2")));

        // Best-effort cleanup of the orphaned second vault secret.
        verify(secretService, times(1)).deleteSecret(eq(secretUuidB), eq(true));

        Assertions.assertEquals(1, service.list(parent).size());
    }

    @Test
    void testUpdate_WithPassword_RotatesAndEvicts() throws Exception {
        SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

        UUID secretUuid = UUID.randomUUID();
        when(secretService.createSecret(any(), any(), any())).thenReturn(secretDtoWithUuid(secretUuid));
        TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
        SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

        service.update(parent, credentialUuid, request("svc-renamed", "newsecret"));

        verify(secretService, times(1)).updateSecret(eq(secretUuid), any());
        verify(credentialVerificationCache, times(1)).evictBySecretUuid(secretUuid);
    }

    @Test
    void testUpdate_WithoutPassword_DoesNotRotateOrEvict() throws Exception {
        SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

        UUID secretUuid = UUID.randomUUID();
        when(secretService.createSecret(any(), any(), any())).thenReturn(secretDtoWithUuid(secretUuid));
        TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
        SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

        service.update(parent, credentialUuid, request("svc-renamed", null));
        service.update(parent, credentialUuid, request("svc-renamed-2", "  "));

        verify(secretService, never()).updateSecret(any(), any());
        verify(credentialVerificationCache, never()).evictBySecretUuid(any());
    }

    @Test
    void testDelete_DeletesSecretRemovesRowAndEvicts() throws Exception {
        SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());

        UUID secretUuid = UUID.randomUUID();
        when(secretService.createSecret(any(), any(), any())).thenReturn(secretDtoWithUuid(secretUuid));
        TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
        SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

        service.delete(parent, credentialUuid);

        verify(secretService, times(1)).deleteSecret(eq(secretUuid), eq(true));
        verify(credentialVerificationCache, times(1)).evictBySecretUuid(secretUuid);
        Assertions.assertTrue(service.list(parent).isEmpty());
    }

    @Test
    void testGetAndList_ParentScoping() throws NotFoundException {
        SecuredParentUUID parent = SecuredParentUUID.fromUUID(profileWithVault.getUuid());
        TspBasicCredentialDto created = service.create(parent, request("svc", "secret"));
        SecuredUUID credentialUuid = SecuredUUID.fromUUID(created.getUuid());

        SecuredParentUUID otherParent = SecuredParentUUID.fromUUID(profileNoVault.getUuid());

        Assertions.assertThrows(NotFoundException.class, () -> service.get(otherParent, credentialUuid));
        Assertions.assertTrue(service.list(otherParent).isEmpty());

        // Sanity: it is reachable through its own parent.
        Assertions.assertEquals(created.getUuid(), service.get(parent, credentialUuid).getUuid());
    }
}
