package com.czertainly.core.util.builders;

import com.czertainly.api.model.client.signing.profile.SigningProfileDto;
import com.czertainly.core.dao.entity.signing.SigningRecord;

import java.time.Instant;
import java.util.UUID;

public class SigningRecordEntityBuilder {

    private SigningProfileDto signingProfile = null;
    private int version = 1;
    private Instant signingTime = Instant.now();

    public static SigningRecordEntityBuilder aSigningRecord() {
        return new SigningRecordEntityBuilder();
    }

    public SigningRecordEntityBuilder withSigningProfile(SigningProfileDto profile) {
        this.signingProfile = profile;
        return this;
    }

    public SigningRecordEntityBuilder withVersion(int version) {
        this.version = version;
        return this;
    }

    public SigningRecordEntityBuilder withSigningTime(Instant time) {
        this.signingTime = time;
        return this;
    }

    public SigningRecord build() {
        SigningRecord record = new SigningRecord();
        record.setUuid(UUID.randomUUID());
        if (signingProfile != null) {
            record.setSigningProfileUuid(UUID.fromString(signingProfile.getUuid()));
            record.setName(signingProfile.getName());
        }
        record.setSigningProfileVersion(version);
        record.setSigningTime(signingTime);
        return record;
    }
}
