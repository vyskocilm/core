package com.czertainly.core.security.authn.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Positive-only, peppered verification cache for TSP credential authentication.
 *
 * <p>Only successful authentication verifications are stored; callers must never invoke {@link #putSuccess}
 * on a failed authentication. The cache key is an HMAC-SHA-256 over {@code secretUuid + ":" + password}
 * using a per-process pepper (never persisted), so raw passwords are never stored or reconstructable from cache state.</p>
 */
public interface CredentialVerificationCache {

    /**
     * @return resolved mapped user UUID if this exact (secret, password) was verified
     *         successfully and is still cached; empty if not cached or already evicted.
     */
    Optional<UUID> getMappedUser(UUID secretUuid, String password);

    /**
     * Cache a SUCCESSFUL verification only. Never call on failure.
     *
     * @param secretUuid    the credential secret UUID
     * @param password      the raw password (used only to derive the HMAC key; not stored)
     * @param mappedUserUuid the user UUID resolved during the successful verification
     */
    void putSuccess(UUID secretUuid, String password, UUID mappedUserUuid);

    /**
     * Evict all cache entries associated with the given secret UUID.
     * Called on secret rotation or deletion so stale positive hits are cleared immediately.
     */
    void evictBySecretUuid(UUID secretUuid);
}
