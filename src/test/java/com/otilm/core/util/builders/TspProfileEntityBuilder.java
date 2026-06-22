package com.otilm.core.util.builders;

import com.otilm.api.model.core.signing.TspAuthenticationMethod;
import com.otilm.core.dao.entity.VaultProfile;
import com.otilm.core.dao.entity.signing.TspProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds an in-memory {@link TspProfile} with valid, unremarkable defaults; tests override only the fields
 * whose values drive the assertion under test. Persistence goes through the real {@code TspProfileRepository}
 * (or service), not this builder — the builder never touches the database.
 */
public final class TspProfileEntityBuilder {

    private String name = "tsp-profile";
    private String description = null;
    private boolean enabled = false;
    private List<TspAuthenticationMethod> allowedAuthenticationMethods = new ArrayList<>();
    private UUID vaultProfileUuid = null;
    private VaultProfile vaultProfile = null;
    private UUID defaultSigningProfileUuid = null;

    public static TspProfileEntityBuilder aTspProfile() {
        return new TspProfileEntityBuilder();
    }

    public TspProfileEntityBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public TspProfileEntityBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public TspProfileEntityBuilder withEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public TspProfileEntityBuilder withAllowedAuthenticationMethods(List<TspAuthenticationMethod> methods) {
        this.allowedAuthenticationMethods = methods;
        return this;
    }

    public TspProfileEntityBuilder withVaultProfileUuid(UUID vaultProfileUuid) {
        this.vaultProfileUuid = vaultProfileUuid;
        return this;
    }

    public TspProfileEntityBuilder withVaultProfile(VaultProfile vaultProfile) {
        this.vaultProfile = vaultProfile;
        return this;
    }

    public TspProfileEntityBuilder withDefaultSigningProfileUuid(UUID defaultSigningProfileUuid) {
        this.defaultSigningProfileUuid = defaultSigningProfileUuid;
        return this;
    }

    public TspProfile build() {
        TspProfile tspProfile = new TspProfile();
        tspProfile.setName(name);
        tspProfile.setDescription(description);
        tspProfile.setEnabled(enabled);
        tspProfile.setAllowedAuthenticationMethods(allowedAuthenticationMethods);
        tspProfile.setDefaultSigningProfileUuid(defaultSigningProfileUuid);
        if (vaultProfile != null) {
            tspProfile.setVaultProfile(vaultProfile);
        } else {
            tspProfile.setVaultProfileUuid(vaultProfileUuid);
        }
        return tspProfile;
    }
}
