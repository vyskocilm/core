package com.czertainly.core.util.builders;

import com.czertainly.api.model.client.signing.profile.SigningProfileDto;

import java.util.UUID;

public class SigningProfileDtoBuilder {

    private String uuid = UUID.randomUUID().toString();
    private String name = "test-signing-profile";

    public static SigningProfileDtoBuilder aSigningProfileDto() {
        return new SigningProfileDtoBuilder();
    }

    public SigningProfileDtoBuilder withUuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public SigningProfileDtoBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public SigningProfileDto build() {
        SigningProfileDto dto = new SigningProfileDto();
        dto.setUuid(uuid);
        dto.setName(name);
        return dto;
    }
}
