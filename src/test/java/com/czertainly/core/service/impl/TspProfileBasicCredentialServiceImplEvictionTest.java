package com.czertainly.core.service.impl;

import com.czertainly.core.config.cache.CacheConfig;
import com.czertainly.core.config.cache.CacheEvictor;
import com.czertainly.core.dao.entity.signing.TspProfile;
import com.czertainly.core.dao.entity.signing.TspProfileBasicCredential;
import com.czertainly.core.dao.repository.signing.TspProfileBasicCredentialRepository;
import com.czertainly.core.security.authn.client.CredentialVerificationCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TspProfileBasicCredentialServiceImplEvictionTest {

    @Mock
    private TspProfileBasicCredentialRepository credentialRepository;

    @Mock
    private CacheEvictor cacheEvictor;

    @Mock
    private CredentialVerificationCache credentialVerificationCache;

    private TspProfileBasicCredentialServiceImpl service;

    private UUID secretUuid;

    @BeforeEach
    void setUp() {
        service = new TspProfileBasicCredentialServiceImpl();
        service.setCredentialRepository(credentialRepository);
        service.setCacheEvictor(cacheEvictor);
        service.setCredentialVerificationCache(credentialVerificationCache);
        secretUuid = UUID.randomUUID();
    }

    @Test
    void evictsProfileAndVerificationCachesWhenCredentialFound() {
        TspProfile profile = new TspProfile();
        profile.setName("p1");
        TspProfileBasicCredential credential = new TspProfileBasicCredential();
        credential.setTspProfile(profile);
        when(credentialRepository.findBySecretUuid(secretUuid)).thenReturn(Optional.of(credential));

        service.evictCachesForSecret(secretUuid);

        verify(cacheEvictor).evict(CacheConfig.TSP_PROFILE_CACHE, "p1");
        verify(credentialVerificationCache).evictBySecretUuid(secretUuid);
    }

    @Test
    void noOpWhenSecretIsNotTspBasicCredential() {
        when(credentialRepository.findBySecretUuid(secretUuid)).thenReturn(Optional.empty());

        service.evictCachesForSecret(secretUuid);

        verifyNoInteractions(cacheEvictor);
        verifyNoInteractions(credentialVerificationCache);
    }
}
