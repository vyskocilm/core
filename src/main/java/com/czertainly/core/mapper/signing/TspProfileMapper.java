package com.czertainly.core.mapper.signing;

import com.otilm.api.model.client.attribute.ResponseAttribute;
import com.otilm.api.model.client.signing.profile.SimplifiedSigningProfileDto;
import com.otilm.api.model.client.signing.protocols.tsp.TspProfileDto;
import com.otilm.api.model.client.signing.protocols.tsp.TspProfileListDto;
import com.czertainly.core.dao.entity.signing.SigningProfile;
import com.czertainly.core.dao.entity.signing.TspProfile;
import com.czertainly.core.dao.entity.signing.TspProfileBasicCredential;
import com.czertainly.core.model.signing.TspProfileModel;
import com.czertainly.core.model.signing.TspProfileModel.BasicCredentialRef;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TspProfileMapper {

    private TspProfileMapper() {
    }

    public static TspProfileDto toDto(TspProfile profile, List<ResponseAttribute> customAttributes) {
        TspProfileDto dto = new TspProfileDto();
        dto.setUuid(profile.getUuid().toString());
        dto.setName(profile.getName());
        dto.setDescription(profile.getDescription());
        dto.setEnabled(profile.isEnabled());
        if (profile.getDefaultSigningProfile() != null) {
            SimplifiedSigningProfileDto signingProfileDto = SigningProfileMapper.toSimpleDto(profile.getDefaultSigningProfile());
            dto.setSigningUrl(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
                    + "/v1/protocols/tsp/" + profile.getName() + "/sign");
            dto.setDefaultSigningProfile(signingProfileDto);
        }
        dto.setCustomAttributes(customAttributes);
        dto.setAllowedAuthenticationMethods(List.copyOf(profile.getAllowedAuthenticationMethods()));
        if (profile.getVaultProfile() != null) {
            dto.setVaultProfile(profile.getVaultProfile().mapToDto());
        }
        return dto;
    }

    public static TspProfileModel toModel(TspProfile profile, List<ResponseAttribute> customAttributes, Map<UUID, String> fingerprintsBySecretUuid) {
        SigningProfile defaultSigningProfile = profile.getDefaultSigningProfile();
        List<BasicCredentialRef> basicCredentialRefs = profile.getBasicCredentials().stream()
                .map(c -> new BasicCredentialRef(c.getUsername(), c.getSecretUuid(), c.getMappedUserUuid(), fingerprintsBySecretUuid.get(c.getSecretUuid())))
                .toList();
        return new TspProfileModel(
                profile.getUuid(),
                profile.getName(),
                profile.getDescription(),
                profile.isEnabled(),
                defaultSigningProfile != null ? defaultSigningProfile.getUuid() : null,
                defaultSigningProfile != null ? defaultSigningProfile.getName() : null,
                customAttributes,
                List.copyOf(profile.getAllowedAuthenticationMethods()),
                basicCredentialRefs,
                profile.getVaultProfileUuid()
        );
    }

    public static TspProfileListDto toListDto(TspProfile profile) {
        TspProfileListDto dto = new TspProfileListDto();
        dto.setUuid(profile.getUuid().toString());
        dto.setName(profile.getName());
        dto.setDescription(profile.getDescription());
        dto.setEnabled(profile.isEnabled());
        if (profile.getDefaultSigningProfile() != null) {
            dto.setDefaultSigningProfile(SigningProfileMapper.toSimpleDto(profile.getDefaultSigningProfile()));
        }
        return dto;
    }
}
