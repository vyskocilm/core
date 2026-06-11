package com.otilm.core.security.authn.client;

import com.otilm.core.util.BaseSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialVerificationCacheTest extends BaseSpringBootTest {

    @Autowired
    private CredentialVerificationCache cache;

    @Test
    void returnsMappedUserOnHit_andEvictionBySecretClearsIt() {
        // given
        UUID secret = UUID.randomUUID();
        UUID mappedUser = UUID.randomUUID();
        assertThat(cache.getMappedUser(secret, "pw")).isEmpty();

        // when
        cache.putSuccess(secret, "pw", mappedUser);

        // then
        assertThat(cache.getMappedUser(secret, "pw")).isEqualTo(Optional.of(mappedUser));

        // when — the secret is evicted
        cache.evictBySecretUuid(secret);

        // then
        assertThat(cache.getMappedUser(secret, "pw")).isEmpty();
    }

    @Test
    void returnsEmpty_whenPasswordWrong() {
        // given
        UUID secret = UUID.randomUUID();
        cache.putSuccess(secret, "right", UUID.randomUUID());

        // when / then
        assertThat(cache.getMappedUser(secret, "wrong")).isEmpty();
    }
}
