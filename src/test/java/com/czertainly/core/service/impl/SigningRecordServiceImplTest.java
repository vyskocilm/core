package com.czertainly.core.service.impl;

import com.czertainly.api.exception.AlreadyExistException;
import com.czertainly.api.exception.AttributeException;
import com.czertainly.api.exception.ConnectorException;
import com.czertainly.api.exception.NotFoundException;
import com.czertainly.api.model.client.certificate.SearchRequestDto;
import com.czertainly.api.model.client.signing.profile.SigningProfileDto;
import com.czertainly.api.model.common.PaginationResponseDto;
import com.czertainly.api.model.core.connector.v2.ConnectorDetailDto;
import com.czertainly.api.model.core.search.FilterFieldSource;
import com.czertainly.api.model.core.search.SearchFieldDataByGroupDto;
import com.czertainly.api.model.core.search.SearchFieldDataDto;
import com.czertainly.api.model.core.signing.signingrecord.SigningRecordDto;
import com.czertainly.api.model.core.signing.signingrecord.SigningRecordListDto;
import com.czertainly.api.model.core.auth.Resource;
import com.czertainly.core.dao.entity.signing.SigningRecord;
import com.czertainly.core.enums.FilterField;
import com.czertainly.core.security.authz.SecuredUUID;
import com.czertainly.core.security.authz.SecurityFilter;
import com.czertainly.core.service.SigningProfileService;
import com.czertainly.core.service.SigningRecordService;
import com.czertainly.core.service.v2.ConnectorService;
import com.czertainly.core.service.writer.signingrecord.SigningRecordWriter;
import com.czertainly.core.util.BaseSpringBootTest;
import com.czertainly.core.util.mocks.SignerConnectorMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static com.czertainly.core.util.builders.ConnectorRequestDtoBuilder.aV2ConnectorRequest;
import static com.czertainly.core.util.builders.SigningProfileRequestDtoBuilder.aSigningProfileRequest;
import static com.czertainly.core.util.builders.SigningRecordEntityBuilder.aSigningRecord;
import static org.junit.jupiter.api.Assertions.*;

class SigningRecordServiceImplTest extends BaseSpringBootTest {

    @Autowired
    private SigningRecordService signingRecordService;

    @Autowired
    private SigningProfileService signingProfileService;

    @Autowired
    private SigningRecordWriter signingRecordWriter;

    @Autowired
    private ConnectorService connectorService;

    private SignerConnectorMock signerConnectorMock;
    private ConnectorDetailDto signerConnector;
    private SigningProfileDto defaultProfile;

    @BeforeEach
    void setUp() throws Exception {
        signerConnectorMock = SignerConnectorMock.start();
        signerConnector = connectorService.createConnector(
                aV2ConnectorRequest()
                        .withName("signer")
                        .withUrl(signerConnectorMock.getUrl())
                        .build()
        );
        defaultProfile = createSigningProfile("default-profile");
    }

    @AfterEach
    void tearDown() {
        signerConnectorMock.stop();
    }

    private SigningProfileDto createSigningProfile(String name)
            throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
        return signingProfileService.createSigningProfile(
                aSigningProfileRequest()
                        .withName(name)
                        .withDelegatedSigning(signerConnector.getUuid())
                        .withRawSigning()
                        .build()
        );
    }

    @Nested
    class GetTests {

        @Test
        void returnsExistingRecord() throws NotFoundException {
            // given
            SigningRecord saved = aSigningRecord()
                    .withSigningProfile(defaultProfile)
                    .build();
            signingRecordWriter.insert(saved);
            SecuredUUID savedUuid = SecuredUUID.fromUUID(saved.getUuid());

            // when
            SigningRecordDto found = signingRecordService.getSigningRecord(savedUuid);

            // then
            assertEquals(saved.getUuid().toString(), found.getUuid());
        }

        @Test
        void throwsNotFoundException_forUnknownUuid() {
            // given
            SecuredUUID unknownUuid = SecuredUUID.fromString(UUID.randomUUID().toString());

            // when
            Executable get = () -> signingRecordService.getSigningRecord(unknownUuid);

            // then
            assertThrows(NotFoundException.class, get);
        }
    }

    @Nested
    class ListTests {

        @Test
        void returnsEmptyList_whenNoRecordsExist() {
            // given
            var searchRequest = new SearchRequestDto();
            SecurityFilter filter = new SecurityFilter();

            // when
            PaginationResponseDto<SigningRecordListDto> response =
                    signingRecordService.listSigningRecords(searchRequest, filter);

            // then
            assertNotNull(response);
            assertEquals(0, response.getTotalItems());
            assertTrue(response.getItems().isEmpty());
        }

        @Test
        void returnsAllSavedRecords() {
            // given
            signingRecordWriter.insert(aSigningRecord().withSigningProfile(defaultProfile).build());
            signingRecordWriter.insert(aSigningRecord().withSigningProfile(defaultProfile).build());
            var searchRequest = new SearchRequestDto();
            SecurityFilter filter = new SecurityFilter();

            // when
            PaginationResponseDto<SigningRecordListDto> response =
                    signingRecordService.listSigningRecords(searchRequest, filter);

            // then
            assertEquals(2, response.getTotalItems());
            assertEquals(2, response.getItems().size());
        }
    }

    @Nested
    class ListByProfileTests {

        @Test
        void returnsOnlyRecords_forRequestedSigningProfile()
                throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
            // given
            var targetProfile = createSigningProfile("target-profile");
            signingRecordWriter.insert(aSigningRecord().withSigningProfile(targetProfile).build());
            signingRecordWriter.insert(aSigningRecord().withSigningProfile(defaultProfile).build());

            SecurityFilter filter = new SecurityFilter();
            SearchRequestDto searchRequest = new SearchRequestDto();

            // when
            var records = signingRecordService.listSigningRecordsForProfile(
                    UUID.fromString(targetProfile.getUuid()),
                    searchRequest,
                    filter);

            // then
            assertEquals(1, records.getTotalItems());
        }

        @Test
        void returnsEmptyList_whenNoRecordsForProfile()
                throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
            // given
            var profileWithNoRecords = createSigningProfile("profile-with-no-records");
            SecurityFilter filter = new SecurityFilter();
            SearchRequestDto searchRequest = new SearchRequestDto();

            // when
            var records = signingRecordService.listSigningRecordsForProfile(
                    UUID.fromString(profileWithNoRecords.getUuid()),
                    searchRequest,
                    filter);

            // then
            assertTrue(records.getItems().isEmpty());
        }
    }

    @Nested
    class ExistsForVersionTests {

        @Test
        void returnsTrue_whenRecordExistsForProfileAndVersion() {
            // given
            int version = 1;
            signingRecordWriter.insert(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .withVersion(version)
                            .build());
            UUID profileUuid = UUID.fromString(defaultProfile.getUuid());

            // when
            boolean exists = signingRecordService.doesSigningRecordExistInternal(profileUuid, version);

            // then
            assertTrue(exists);
        }

        @Test
        void returnsFalse_whenNoRecordForVersion() {
            // given
            int nonExistentVersion = 99;
            int versionWithRecord = 1;
            signingRecordWriter.insert(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .withVersion(versionWithRecord)
                            .build());
            UUID profileUuid = UUID.fromString(defaultProfile.getUuid());

            // when
            boolean exists = signingRecordService.doesSigningRecordExistInternal(profileUuid, nonExistentVersion);

            // then
            assertFalse(exists);
        }
    }

    @Nested
    class SearchableFieldsTests {

        @Test
        void returnsPropertyGroupWithAllSigningRecordFields() {
            // when
            List<SearchFieldDataByGroupDto> groups = signingRecordService.getSearchableFieldInformation();

            // then
            assertNotNull(groups);
            assertEquals(1, groups.stream()
                    .filter(g -> g.getFilterFieldSource() == FilterFieldSource.PROPERTY)
                    .count());

            List<SearchFieldDataDto> propertyFields = propertyFieldsOf(groups);
            assertEquals(FilterField.getEnumsForResource(Resource.SIGNING_RECORD).size(), propertyFields.size());
        }

        @Test
        void exposesSigningProfileNamesInSigningProfileField() {
            // when
            List<SearchFieldDataByGroupDto> groups = signingRecordService.getSearchableFieldInformation();

            // then
            SearchFieldDataDto signingProfileField = propertyFieldsOf(groups).stream()
                    .filter(f -> f.getFieldIdentifier().equals(FilterField.SIGNING_RECORD_SIGNING_PROFILE.name()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(((List<?>) signingProfileField.getValue()).contains(defaultProfile.getName()));
        }

        private List<SearchFieldDataDto> propertyFieldsOf(List<SearchFieldDataByGroupDto> groups) {
            return groups.stream()
                    .filter(g -> g.getFilterFieldSource() == FilterFieldSource.PROPERTY)
                    .map(SearchFieldDataByGroupDto::getSearchFieldData)
                    .flatMap(List::stream)
                    .toList();
        }
    }
}
