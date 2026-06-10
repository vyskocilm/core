package com.czertainly.core.security.authn.client;

import com.czertainly.core.util.BaseSpringBootTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

class CredentialVerificationCacheTest extends BaseSpringBootTest {

    @Autowired
    private CredentialVerificationCache cache;

    @Test
    void positiveHitReturnsMappedUser_andEvictionBySecretClears() {
        UUID secret = UUID.randomUUID();
        UUID mappedUser = UUID.randomUUID();

        Assertions.assertTrue(cache.getMappedUser(secret, "pw").isEmpty());

        cache.putSuccess(secret, "pw", mappedUser);
        Assertions.assertEquals(Optional.of(mappedUser), cache.getMappedUser(secret, "pw"));

        cache.evictBySecretUuid(secret);
        Assertions.assertTrue(cache.getMappedUser(secret, "pw").isEmpty());
    }

    @Test
    void wrongPasswordDoesNotHit() {
        UUID secret = UUID.randomUUID();
        cache.putSuccess(secret, "right", UUID.randomUUID());
        Assertions.assertTrue(cache.getMappedUser(secret, "wrong").isEmpty());
    }
}
