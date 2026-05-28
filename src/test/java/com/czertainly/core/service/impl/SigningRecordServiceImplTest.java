package com.czertainly.core.service.impl;

import com.czertainly.api.exception.AlreadyExistException;
import com.czertainly.api.exception.AttributeException;
import com.czertainly.api.exception.ConnectorException;
import com.czertainly.api.exception.NotFoundException;
import com.czertainly.api.model.client.certificate.SearchRequestDto;
import com.czertainly.api.model.client.signing.profile.SigningProfileDto;
import com.czertainly.api.model.common.PaginationResponseDto;
import com.czertainly.api.model.core.connector.v2.ConnectorDetailDto;
import com.czertainly.api.model.core.signing.signingrecord.SigningRecordDto;
import com.czertainly.api.model.core.signing.signingrecord.SigningRecordListDto;
import com.czertainly.core.security.authz.SecuredUUID;
import com.czertainly.core.security.authz.SecurityFilter;
import com.czertainly.core.service.SigningProfileService;
import com.czertainly.core.service.SigningRecordService;
import com.czertainly.core.service.v2.ConnectorService;
import com.czertainly.core.util.BaseSpringBootTest;
import com.czertainly.core.util.mocks.SignerConnectorMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;

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
    class SaveTests {

        @Test
        void persistsRecord_andAssignsUuid() {
            // when
            SigningRecordDto saved = signingRecordService.saveSigningRecord(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .build());

            // then
            assertNotNull(saved.getUuid());
        }

        @Test
        void persistsSigningTime() {
            // given
            ZonedDateTime signingTime = ZonedDateTime.now();

            // when
            SigningRecordDto saved = signingRecordService.saveSigningRecord(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .withSigningTime(signingTime)
                            .build());

            // then: zone ID is dropped by the DB round-trip; compare the instant
            assertEquals(signingTime.toInstant(), saved.getSigningTime().toInstant());
        }
    }

    @Nested
    class GetTests {

        @Test
        void returnsExistingRecord() throws NotFoundException {
            // given
            SigningRecordDto saved = signingRecordService.saveSigningRecord(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .build());
            SecuredUUID savedUuid = SecuredUUID.fromString(saved.getUuid());

            // when
            SigningRecordDto found = signingRecordService.getSigningRecord(savedUuid);

            // then
            assertEquals(saved.getUuid(), found.getUuid());
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
            signingRecordService.saveSigningRecord(aSigningRecord().withSigningProfile(defaultProfile).build());
            signingRecordService.saveSigningRecord(aSigningRecord().withSigningProfile(defaultProfile).build());
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
            signingRecordService.saveSigningRecord(aSigningRecord().withSigningProfile(targetProfile).build());
            signingRecordService.saveSigningRecord(aSigningRecord().withSigningProfile(defaultProfile).build());

            SecurityFilter filter = new SecurityFilter();

            // when
            var records = signingRecordService.listSigningRecordsAssociatedWithSigningProfile(
                    SecuredUUID.fromString(targetProfile.getUuid()),
                    filter);

            // then
            assertEquals(1, records.size());
        }

        @Test
        void returnsEmptyList_whenNoRecordsForProfile()
                throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
            // given
            var profileWithNoRecords = createSigningProfile("profile-with-no-records");
            SecurityFilter filter = new SecurityFilter();

            // when
            var records = signingRecordService.listSigningRecordsAssociatedWithSigningProfile(
                    SecuredUUID.fromString(profileWithNoRecords.getUuid()), filter);

            // then
            assertTrue(records.isEmpty());
        }
    }

    @Nested
    class ExistsForVersionTests {

        @Test
        void returnsTrue_whenRecordExistsForProfileAndVersion() {
            // given
            int version = 1;
            signingRecordService.saveSigningRecord(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .withVersion(version)
                            .build());
            SecuredUUID profileUuid = SecuredUUID.fromString(defaultProfile.getUuid());

            // when
            boolean exists = signingRecordService.doesSigningRecordExistForVersion(profileUuid, version);

            // then
            assertTrue(exists);
        }

        @Test
        void returnsFalse_whenNoRecordForVersion() {
            // given
            int nonExistentVersion = 99;
            int versionWithRecord = 1;
            signingRecordService.saveSigningRecord(
                    aSigningRecord()
                            .withSigningProfile(defaultProfile)
                            .withVersion(versionWithRecord)
                            .build());
            SecuredUUID profileUuid = SecuredUUID.fromString(defaultProfile.getUuid());

            // when
            boolean exists = signingRecordService.doesSigningRecordExistForVersion(profileUuid, nonExistentVersion);

            // then
            assertFalse(exists);
        }
    }

    @Nested
    class ValidateTests {

        @Test
        void throwsUnsupportedOperationException_forExistingRecord() {
            // given
            SigningRecordDto saved = signingRecordService.saveSigningRecord(
                    aSigningRecord().withSigningProfile(defaultProfile).build());
            SecuredUUID savedUuid = SecuredUUID.fromString(saved.getUuid());

            // when
            Executable validate = () -> signingRecordService.validateSigningRecord(savedUuid);

            // then
            assertThrows(UnsupportedOperationException.class, validate);
        }

        @Test
        void throwsNotFoundException_forUnknownUuid() {
            // given
            SecuredUUID unknownUuid = SecuredUUID.fromString(UUID.randomUUID().toString());

            // when
            Executable validate = () -> signingRecordService.validateSigningRecord(unknownUuid);

            // then
            assertThrows(NotFoundException.class, validate);
        }
    }

    @Nested
    class SearchableFieldsTests {

        @Test
        void returnsEmptyList() {
            // when
            var fields = signingRecordService.getSearchableFieldInformation();

            // then
            assertNotNull(fields);
            assertTrue(fields.isEmpty());
        }
    }
}
