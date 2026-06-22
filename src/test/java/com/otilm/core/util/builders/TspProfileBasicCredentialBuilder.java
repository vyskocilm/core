package com.otilm.core.util.builders;

import com.otilm.core.dao.entity.signing.TspProfile;
import com.otilm.core.dao.entity.signing.TspProfileBasicCredential;

import java.util.UUID;

/**
 * Builds an in-memory {@link TspProfileBasicCredential} with valid, unremarkable defaults; tests override only
 * the fields whose values drive the assertion under test. Persistence goes through the real repository (or
 * service), not this builder — the builder never touches the database.
 */
public final class TspProfileBasicCredentialBuilder {

    private TspProfile tspProfile = null;
    private UUID tspProfileUuid = null;
    private String username = "svc-account";
    private UUID secretUuid = UUID.randomUUID();
    private UUID mappedUserUuid = UUID.randomUUID();

    public static TspProfileBasicCredentialBuilder aTspProfileBasicCredential() {
        return new TspProfileBasicCredentialBuilder();
    }

    public TspProfileBasicCredentialBuilder withTspProfile(TspProfile tspProfile) {
        this.tspProfile = tspProfile;
        return this;
    }

    public TspProfileBasicCredentialBuilder withTspProfileUuid(UUID tspProfileUuid) {
        this.tspProfileUuid = tspProfileUuid;
        return this;
    }

    public TspProfileBasicCredentialBuilder withUsername(String username) {
        this.username = username;
        return this;
    }

    public TspProfileBasicCredentialBuilder withSecretUuid(UUID secretUuid) {
        this.secretUuid = secretUuid;
        return this;
    }

    public TspProfileBasicCredentialBuilder withMappedUserUuid(UUID mappedUserUuid) {
        this.mappedUserUuid = mappedUserUuid;
        return this;
    }

    public TspProfileBasicCredential build() {
        TspProfileBasicCredential credential = new TspProfileBasicCredential();
        credential.setUsername(username);
        credential.setSecretUuid(secretUuid);
        credential.setMappedUserUuid(mappedUserUuid);
        if (tspProfile != null) {
            credential.setTspProfile(tspProfile);
        } else {
            credential.setTspProfileUuid(tspProfileUuid);
        }
        return credential;
    }
}
