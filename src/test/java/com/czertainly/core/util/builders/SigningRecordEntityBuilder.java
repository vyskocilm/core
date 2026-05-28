package com.czertainly.core.util.builders;

import com.czertainly.api.model.client.signing.profile.SigningProfileDto;
import com.czertainly.api.model.client.signing.profile.SigningProfileListDto;
import com.czertainly.api.model.core.signing.signingrecord.SigningRecordDto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public class SigningRecordEntityBuilder {

    private SigningProfileDto signingProfile = null;
    private int version = 1;
    private ZonedDateTime signingTime = ZonedDateTime.now();

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

    public SigningRecordEntityBuilder withSigningTime(ZonedDateTime time) {
        this.signingTime = time;
        return this;
    }

    public SigningRecordDto build() {
        SigningRecordDto dto = new SigningRecordDto();
        if (signingProfile != null) {
            SigningProfileListDto profileRef = new SigningProfileListDto();
            profileRef.setUuid(signingProfile.getUuid());
            profileRef.setName(signingProfile.getName());
            profileRef.setVersion(version);
            dto.setSigningProfile(profileRef);
        }
        dto.setSigningTime(signingTime);
        return dto;
    }
}
