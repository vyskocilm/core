package com.czertainly.core.service;

import com.czertainly.api.exception.*;
import com.czertainly.api.model.client.attribute.RequestAttributeV3;
import com.czertainly.api.model.client.attribute.ResponseAttribute;
import com.czertainly.api.model.client.attribute.ResponseAttributeV3;
import com.czertainly.api.model.client.attribute.custom.CustomAttributeCreateRequestDto;
import com.czertainly.api.model.client.signing.profile.SigningProfileDto;
import com.czertainly.api.model.client.signing.profile.SigningProfileRequestDto;
import com.czertainly.api.model.client.signing.profile.scheme.DelegatedSigningRequestDto;
import com.czertainly.api.model.client.signing.profile.workflow.TimestampingWorkflowRequestDto;
import com.czertainly.api.model.client.signing.protocols.tsp.TspProfileDto;
import com.czertainly.api.model.client.signing.protocols.tsp.TspProfileListDto;
import com.czertainly.api.model.common.BulkActionMessageDto;
import com.czertainly.api.model.common.PaginationResponseDto;
import com.czertainly.api.model.common.attribute.common.content.AttributeContentType;
import com.czertainly.api.model.core.auth.Resource;
import com.czertainly.api.model.core.connector.v2.ConnectorDetailDto;
import com.czertainly.core.security.authz.SecuredUUID;
import com.czertainly.core.security.authz.SecurityFilter;
import com.czertainly.core.service.v2.ConnectorService;
import com.czertainly.core.util.BaseSpringBootTest;
import com.czertainly.core.util.mocks.SignerConnectorMock;
import com.czertainly.core.util.mocks.TimestampingFormatterConnectorMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static com.czertainly.core.util.builders.ConnectorRequestDtoBuilder.aV2ConnectorRequest;
import static com.czertainly.core.util.builders.RequestAttributeV3Builder.aCustomAttribute;
import static com.czertainly.core.util.builders.SearchRequestDtoBuilder.aSearchRequest;
import static com.czertainly.core.util.builders.TspProfileRequestDtoBuilder.aTspProfileRequest;
import static com.czertainly.core.util.builders.TspProfileRequestDtoBuilder.aTspProfileRequestFromProfile;
import static org.junit.jupiter.api.Assertions.*;

class TspProfileServiceImplTest extends BaseSpringBootTest {

    private static final String TSP_CUSTOM_ATTR_NAME = "tspTestAttribute";

    private String tspCustomAttrUuid;

    @Autowired
    private TspProfileService tspService;

    @Autowired
    private SigningProfileService signingProfileService;

    @Autowired
    private ConnectorService connectorService;

    @Autowired
    private AttributeService attributeService;

    private SignerConnectorMock signerMock;
    private TimestampingFormatterConnectorMock formatterMock;
    private ConnectorDetailDto delegatedConnector;
    private ConnectorDetailDto formatterConnector;

    private TspProfileDto defaultTspProfile;

    @BeforeEach
    void setUp() throws Exception {
        signerMock = SignerConnectorMock.start();
        formatterMock = TimestampingFormatterConnectorMock.start();
        formatterMock.stubFormatterAttributes();

        delegatedConnector = connectorService.createConnector(
                aV2ConnectorRequest()
                        .withName("default-delegated-signer")
                        .withUrl(signerMock.getUrl())
                        .build());
        formatterConnector = connectorService.createConnector(
                aV2ConnectorRequest()
                        .withName("default-timestamping-formatter")
                        .withUrl(formatterMock.getUrl())
                        .build());

        registerTspCustomAttribute(TSP_CUSTOM_ATTR_NAME);
        defaultTspProfile = tspService.createTspProfile(
                aTspProfileRequest()
                        .withName("default-tsp-profile")
                        .build());
    }

    @AfterEach
    void tearDown() {
        signerMock.stop();
        formatterMock.stop();
    }


    // ──────────────────────────────────────────────────────────────────────────
    // List
    // ──────────────────────────────────────────────────────────────────────────

    private void assertAttributeValue(List<ResponseAttribute> attributes, String name, Object expectedValue) {
        ResponseAttributeV3 attr = attributes.stream()
                .filter(a -> name.equals(a.getName()))
                .map(a -> (ResponseAttributeV3) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Attribute '" + name + "' not found"));
        assertEquals(expectedValue, attr.getContent().getFirst().getData());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Get
    // ──────────────────────────────────────────────────────────────────────────

    private void registerTspCustomAttribute(String name) throws AlreadyExistException, AttributeException {
        CustomAttributeCreateRequestDto request = new CustomAttributeCreateRequestDto();
        request.setName(name);
        request.setLabel(name);
        request.setContentType(AttributeContentType.STRING);
        request.setDescription("test custom attribute for TSP profile");
        request.setResources(List.of(Resource.TSP_PROFILE));
        tspCustomAttrUuid = attributeService.createCustomAttribute(request).getUuid();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Find all names
    // ──────────────────────────────────────────────────────────────────────────

    private SigningProfileDto createTimestampingSigningProfile(String name)
            throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
        DelegatedSigningRequestDto scheme = new DelegatedSigningRequestDto();
        scheme.setConnectorUuid(UUID.fromString(delegatedConnector.getUuid()));
        TimestampingWorkflowRequestDto workflow = new TimestampingWorkflowRequestDto();
        workflow.setSignatureFormatterConnectorUuid(UUID.fromString(formatterConnector.getUuid()));
        SigningProfileRequestDto request = new SigningProfileRequestDto();
        request.setName(name);
        request.setSigningScheme(scheme);
        request.setWorkflow(workflow);
        return signingProfileService.createSigningProfile(request);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Create
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class ListTests {

        @Test
        void returnsExistingEntries() throws AlreadyExistException, AttributeException, NotFoundException {
            // given
            TspProfileDto second = tspService.createTspProfile(
                    aTspProfileRequest().withName("second-tsp-profile").build());
            TspProfileDto third = tspService.createTspProfile(
                    aTspProfileRequest().withName("third-tsp-profile").build());

            // when
            PaginationResponseDto<TspProfileListDto> response = tspService.listTspProfiles(
                    aSearchRequest().build(), SecurityFilter.create());

            // then
            assertNotNull(response);
            assertEquals(3, response.getTotalItems());
            var uuids = response.getItems().stream().map(TspProfileListDto::getUuid).toList();
            assertTrue(uuids.contains(defaultTspProfile.getUuid()));
            assertTrue(uuids.contains(second.getUuid()));
            assertTrue(uuids.contains(third.getUuid()));
        }

        @Test
        void emptyWhenNoneExist() throws NotFoundException {
            // given: remove the default profile created in setup
            tspService.deleteTspProfile(SecuredUUID.fromString(defaultTspProfile.getUuid()));

            // when
            PaginationResponseDto<TspProfileListDto> response = tspService.listTspProfiles(
                    aSearchRequest().build(), SecurityFilter.create());

            // then
            assertEquals(0, response.getTotalItems());
            assertTrue(response.getItems().isEmpty());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Update
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class GetTests {

        @Test
        void returnsCorrectTspProfile() throws NotFoundException {
            // when
            TspProfileDto dto = tspService.getTspProfile(SecuredUUID.fromString(defaultTspProfile.getUuid()));

            // then
            assertNotNull(dto);
            assertEquals(defaultTspProfile.getUuid(), dto.getUuid());
            assertEquals(defaultTspProfile.getName(), dto.getName());
            assertEquals(defaultTspProfile.getDescription(), dto.getDescription());
        }

        @Test
        void notFound_throwsNotFoundException() {
            // when
            Executable get = () -> tspService.getTspProfile(
                    SecuredUUID.fromString("00000000-0000-0000-0000-000000000001"));

            // then
            assertThrows(NotFoundException.class, get);
        }

        @Test
        void entity_returnsCorrectEntity() throws NotFoundException {
            // when
            var entity = tspService.getTspProfileEntity(SecuredUUID.fromString(defaultTspProfile.getUuid()));

            // then
            assertNotNull(entity);
            assertEquals(UUID.fromString(defaultTspProfile.getUuid()), entity.getUuid());
            assertEquals(defaultTspProfile.getName(), entity.getName());
        }

        @Test
        void entity_notFound_throwsNotFoundException() {
            // when
            Executable get = () -> tspService.getTspProfileEntity(
                    SecuredUUID.fromString("00000000-0000-0000-0000-000000000001"));

            // then
            assertThrows(NotFoundException.class, get);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class FindAllNamesTests {

        @Test
        void returnsAllWhenMultipleExist() throws AlreadyExistException, AttributeException, NotFoundException {
            // given
            TspProfileDto second = tspService.createTspProfile(
                    aTspProfileRequest().withName("second-tsp-profile").build());
            TspProfileDto third = tspService.createTspProfile(
                    aTspProfileRequest().withName("third-tsp-profile").build());

            // when
            List<String> names = tspService.findAllNames();

            // then
            assertEquals(3, names.size());
            assertTrue(names.contains(defaultTspProfile.getName()));
            assertTrue(names.contains(second.getName()));
            assertTrue(names.contains(third.getName()));
        }

        @Test
        void emptyWhenNoneExist() throws NotFoundException {
            // given: remove the default profile created in setup
            tspService.deleteTspProfile(SecuredUUID.fromString(defaultTspProfile.getUuid()));

            // when
            List<String> names = tspService.findAllNames();

            // then
            assertTrue(names.isEmpty());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Enable / disable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class CreateTests {

        @Test
        void withoutDefaultSigningProfile_setsExpectedAttributes()
                throws AlreadyExistException, AttributeException, NotFoundException {
            // when
            TspProfileDto dto = tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("new-tsp-profile")
                            .withDescription("New TSP profile description")
                            .build());

            // then
            assertNotNull(dto.getUuid());
            assertEquals("new-tsp-profile", dto.getName());
            assertEquals("New TSP profile description", dto.getDescription());
            assertNull(dto.getDefaultSigningProfile());

            // and: persisted state matches
            TspProfileDto fromService = tspService.getTspProfile(SecuredUUID.fromString(dto.getUuid()));
            assertEquals("new-tsp-profile", fromService.getName());
            assertNull(fromService.getDefaultSigningProfile());
        }

        @Test
        void withDefaultSigningProfile_linksTimestampingProfile()
                throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
            // given: a TIMESTAMPING signing profile eligible as a TSP default
            SigningProfileDto timestampingProfile = createTimestampingSigningProfile("tsp-signing-profile");

            // when
            TspProfileDto dto = tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("tsp-with-default-profile")
                            .withDescription("TSP with default signing profile")
                            .withDefaultSigningProfile(UUID.fromString(timestampingProfile.getUuid()))
                            .build());

            // then
            assertEquals("tsp-with-default-profile", dto.getName());
            assertEquals(timestampingProfile.getUuid(), dto.getDefaultSigningProfile().getUuid());

            // and: persisted state matches
            TspProfileDto fromService = tspService.getTspProfile(SecuredUUID.fromString(dto.getUuid()));
            assertEquals(timestampingProfile.getUuid(), fromService.getDefaultSigningProfile().getUuid());
        }

        @Test
        void defaultSigningProfileNotFound_throwsNotFoundException() {
            // given
            var nonExistentUuid = UUID.fromString("00000000-0000-0000-0000-000000000099");

            // when
            Executable create = () -> tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("tsp-nonexistent-profile")
                            .withDefaultSigningProfile(nonExistentUuid)
                            .build());

            // then
            assertThrows(NotFoundException.class, create);
        }

    }

    // ──────────────────────────────────────────────────────────────────────────
    // Custom attributes
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class UpdateTests {

        @Test
        void updatesNameAndDescription() throws AlreadyExistException, AttributeException, NotFoundException {
            SecuredUUID profileToUpdateUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());

            // when
            TspProfileDto dto = tspService.updateTspProfile(
                    profileToUpdateUuid,
                    aTspProfileRequest()
                            .withName("updated-tsp-profile")
                            .withDescription("Updated description")
                            .build());

            // then
            assertEquals(defaultTspProfile.getUuid(), dto.getUuid());
            assertEquals("updated-tsp-profile", dto.getName());
            assertEquals("Updated description", dto.getDescription());
            assertNull(dto.getDefaultSigningProfile());

            // and: persisted state matches
            TspProfileDto fromService = tspService.getTspProfile(profileToUpdateUuid);
            assertEquals("updated-tsp-profile", fromService.getName());
            assertEquals("Updated description", fromService.getDescription());
        }

        @Test
        void withDefaultSigningProfile_linksTimestampingProfile()
                throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
            // given: a TIMESTAMPING signing profile
            SigningProfileDto timestampingProfile = createTimestampingSigningProfile("tsp-update-default-profile");
            SecuredUUID profileToUpdateUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());

            // when
            TspProfileDto dto = tspService.updateTspProfile(
                    profileToUpdateUuid,
                    aTspProfileRequest()
                            .withName("updated-tsp-with-profile")
                            .withDefaultSigningProfile(UUID.fromString(timestampingProfile.getUuid()))
                            .build());

            // then
            assertEquals("updated-tsp-with-profile", dto.getName());
            assertEquals(timestampingProfile.getUuid(), dto.getDefaultSigningProfile().getUuid());

            // and: persisted state matches
            TspProfileDto fromService = tspService.getTspProfile(profileToUpdateUuid);
            assertEquals(timestampingProfile.getUuid(), fromService.getDefaultSigningProfile().getUuid());
        }

        @Test
        void notFound_throwsNotFoundException() {
            // given
            SecuredUUID nonExistentProfileUuid = SecuredUUID.fromString("00000000-0000-0000-0000-000000000001");

            // when
            Executable update = () -> tspService.updateTspProfile(
                    nonExistentProfileUuid,
                    aTspProfileRequest().withName("does-not-matter").build());

            // then
            assertThrows(NotFoundException.class, update);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Name uniqueness
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class DeleteTests {

        @Test
        void removesProfileFromService() throws NotFoundException {
            // given
            var profileUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());

            // when
            tspService.deleteTspProfile(profileUuid);

            // then
            Executable get = () -> tspService.getTspProfile(profileUuid);
            assertThrows(NotFoundException.class, get);
        }

        @Test
        void notFound_throwsNotFoundException() {
            SecuredUUID nonExistentProfileUuid = SecuredUUID.fromString("00000000-0000-0000-0000-000000000001");

            // when
            Executable delete = () -> tspService.deleteTspProfile(nonExistentProfileUuid);

            // then
            assertThrows(NotFoundException.class, delete);
        }

        @Test
        void withLinkedSigningProfile_throwsValidationException()
                throws AlreadyExistException, AttributeException, ConnectorException, NotFoundException {
            // given: a TIMESTAMPING signing profile linked against the TSP profile
            SigningProfileDto timestampingProfile = createTimestampingSigningProfile("a-timestamping-profile-to-link");
            SecuredUUID tspProfileUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());
            SecuredUUID signingProfileUuid = SecuredUUID.fromString(timestampingProfile.getUuid());

            signingProfileService.activateTsp(signingProfileUuid, tspProfileUuid);

            // when
            Executable delete = () -> tspService.deleteTspProfile(tspProfileUuid);

            // then: delete is blocked and the TSP profile remains accessible
            assertThrows(ValidationException.class, delete);
            assertDoesNotThrow(() -> tspService.getTspProfile(SecuredUUID.fromString(defaultTspProfile.getUuid())));
        }

        @Nested
        class BulkDeleteTests {

            @Test
            void removesAllEntities() throws AlreadyExistException, AttributeException, NotFoundException {
                // given
                TspProfileDto second = tspService.createTspProfile(
                        aTspProfileRequest().withName("second-tsp-profile").build());
                var firstProfileUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());
                var secondProfileUuid = SecuredUUID.fromString(second.getUuid());

                // when
                List<BulkActionMessageDto> messages = tspService.bulkDeleteTspProfiles(
                        List.of(firstProfileUuid, secondProfileUuid));

                // then
                assertTrue(messages.isEmpty(), "Expected no errors but got: " + messages);
                assertThrows(NotFoundException.class, () -> tspService.getTspProfile(firstProfileUuid));
                assertThrows(NotFoundException.class, () -> tspService.getTspProfile(secondProfileUuid));
            }

            @Test
            void partialFailure_returnsErrorMessages() throws NotFoundException {
                // given
                var nonExistentUuid = SecuredUUID.fromUUID(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                var firstUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());

                // when
                List<BulkActionMessageDto> messages = tspService.bulkDeleteTspProfiles(
                        List.of(firstUuid, nonExistentUuid));

                // then
                assertEquals(1, messages.size(), "Expected exactly one error for the unknown profile");
                assertEquals(nonExistentUuid.toString(), messages.getFirst().getUuid());
                assertThrows(NotFoundException.class, () -> tspService.getTspProfile(firstUuid));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    class EnableDisableTests {

        @Test
        void enable_setsEnabledTrue() throws NotFoundException {
            // given: profiles start disabled by default
            TspProfileDto tspProfileDto = defaultTspProfile;
            SecuredUUID profileUuid = SecuredUUID.fromString(tspProfileDto.getUuid());
            assertFalse(tspProfileDto.isEnabled());

            // when
            tspService.enableTspProfile(profileUuid);

            // then
            assertTrue(tspService.getTspProfile(profileUuid).isEnabled());
        }

        @Test
        void disable_setsEnabledFalse() throws NotFoundException {
            // given: profile forced to enabled state
            SecuredUUID profileUuid = SecuredUUID.fromString(defaultTspProfile.getUuid());
            tspService.enableTspProfile(profileUuid);

            // when
            tspService.disableTspProfile(profileUuid);

            // then
            assertFalse(tspService.getTspProfile(profileUuid).isEnabled());
        }

        @Test
        void enable_notFound_throwsNotFoundException() {
            // given
            SecuredUUID unknownUuid = SecuredUUID.fromString("00000000-0000-0000-0000-000000000001");

            // when
            Executable enable = () -> tspService.enableTspProfile(unknownUuid);

            // then
            assertThrows(NotFoundException.class, enable);
        }

        @Test
        void disable_notFound_throwsNotFoundException() {
            // given
            SecuredUUID unknownUuid = SecuredUUID.fromString("00000000-0000-0000-0000-000000000001");

            // when
            Executable disable = () -> tspService.disableTspProfile(unknownUuid);

            // then
            assertThrows(NotFoundException.class, disable);
        }

        @Test
        void bulkEnable_enablesAll() throws AlreadyExistException, AttributeException, NotFoundException {
            // given: two TSP profiles, both disabled by default
            TspProfileDto first = defaultTspProfile;
            TspProfileDto second = tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("second-tsp-profile")
                            .build());
            SecuredUUID firstProfileUuid = SecuredUUID.fromString(first.getUuid());
            SecuredUUID secondProfileUuid = SecuredUUID.fromString(second.getUuid());
            assertFalse(first.isEnabled());
            assertFalse(second.isEnabled());

            // when
            List<BulkActionMessageDto> messages = tspService.bulkEnableTspProfiles(List.of(
                    firstProfileUuid,
                    secondProfileUuid));

            // then
            assertTrue(messages.isEmpty(), "Expected no errors but got: " + messages);
            assertTrue(tspService.getTspProfile(firstProfileUuid).isEnabled());
            assertTrue(tspService.getTspProfile(secondProfileUuid).isEnabled());
        }

        @Test
        void bulkDisable_disablesAll() throws AlreadyExistException, AttributeException, NotFoundException {
            // given: two pre-enabled TSP profiles
            TspProfileDto first = defaultTspProfile;
            TspProfileDto second = tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("second-tsp-profile")
                            .build());
            SecuredUUID firstProfileUuid = SecuredUUID.fromString(first.getUuid());
            SecuredUUID secondProfileUuid = SecuredUUID.fromString(second.getUuid());
            tspService.enableTspProfile(firstProfileUuid);
            tspService.enableTspProfile(secondProfileUuid);

            // when
            List<BulkActionMessageDto> messages = tspService.bulkDisableTspProfiles(List.of(
                    firstProfileUuid,
                    secondProfileUuid));

            // then
            assertTrue(messages.isEmpty(), "Expected no errors but got: " + messages);
            assertFalse(tspService.getTspProfile(firstProfileUuid).isEnabled());
            assertFalse(tspService.getTspProfile(secondProfileUuid).isEnabled());
        }

        @Test
        void bulkEnable_nonExistentUuid_returnsErrorMessage() {
            // given
            var nonExistentUuid = SecuredUUID.fromUUID(UUID.fromString("00000000-0000-0000-0000-000000000001"));

            // when
            List<BulkActionMessageDto> messages = tspService.bulkEnableTspProfiles(List.of(nonExistentUuid));

            // then
            assertEquals(1, messages.size());
            assertEquals(nonExistentUuid.toString(), messages.getFirst().getUuid());
            assertNotNull(messages.getFirst().getMessage());
        }

        @Test
        void bulkDisable_nonExistentUuid_returnsErrorMessage() {
            // given
            var nonExistentUuid = SecuredUUID.fromUUID(UUID.fromString("00000000-0000-0000-0000-000000000001"));

            // when
            List<BulkActionMessageDto> messages = tspService.bulkDisableTspProfiles(List.of(nonExistentUuid));

            // then
            assertEquals(1, messages.size());
            assertEquals(nonExistentUuid.toString(), messages.getFirst().getUuid());
            assertNotNull(messages.getFirst().getMessage());
        }
    }

    @Nested
    class CustomAttributeTests {

        @Test
        void create_withCustomAttributes_returnedInDto()
                throws AlreadyExistException, AttributeException, NotFoundException {
            // given
            var tspAttrValue = "tsp-value-on-create";
            RequestAttributeV3 tspAttr = aCustomAttribute()
                    .withUuid(tspCustomAttrUuid)
                    .withName(TSP_CUSTOM_ATTR_NAME)
                    .withStringContent(tspAttrValue)
                    .build();

            // when
            TspProfileDto dto = tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("tsp-with-custom-attr")
                            .withCustomAttributes(List.of(tspAttr))
                            .build());

            // then
            assertFalse(dto.getCustomAttributes().isEmpty(), "Custom attributes should be returned in the create DTO");
            assertAttributeValue(dto.getCustomAttributes(), TSP_CUSTOM_ATTR_NAME, tspAttrValue);
        }

        @Test
        void update_withCustomAttributes_replacesValue()
                throws AlreadyExistException, AttributeException, NotFoundException {
            // given: a TSP profile created with an initial attribute value
            TspProfileDto created = tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName("tsp-update-custom-attr")
                            .withCustomAttributes(List.of(aCustomAttribute()
                                    .withUuid(tspCustomAttrUuid)
                                    .withName(TSP_CUSTOM_ATTR_NAME)
                                    .withStringContent("initial-value")
                                    .build()))
                            .build());
            var updatedValue = "updated-value";

            // when
            TspProfileDto updated = tspService.updateTspProfile(
                    SecuredUUID.fromString(created.getUuid()),
                    aTspProfileRequestFromProfile(created)
                            .withCustomAttributes(List.of(aCustomAttribute()
                                    .withUuid(tspCustomAttrUuid)
                                    .withName(TSP_CUSTOM_ATTR_NAME)
                                    .withStringContent(updatedValue)
                                    .build()))
                            .build());

            // then
            assertFalse(updated.getCustomAttributes().isEmpty());
            assertAttributeValue(updated.getCustomAttributes(), TSP_CUSTOM_ATTR_NAME, updatedValue);
        }
    }

    @Nested
    class NameUniquenessTests {

        @Test
        void create_duplicateName_throwsAlreadyExistException() {
            // given: a TSP profile already exists with the name "default-tsp-profile" from setup
            String duplicitName = defaultTspProfile.getName();

            // when
            Executable create = () -> tspService.createTspProfile(
                    aTspProfileRequest()
                            .withName(duplicitName)
                            .build());

            // then
            assertThrows(AlreadyExistException.class, create);
        }

        @Test
        void update_duplicateName_throwsAlreadyExistException()
                throws AlreadyExistException, AttributeException, NotFoundException {
            // given
            String duplicitName = defaultTspProfile.getName();
            TspProfileDto profileToUpdate = tspService.createTspProfile(
                    aTspProfileRequest().withName("second-tsp-profile").build());
            SecuredUUID profileToUpdateUuid = SecuredUUID.fromString(profileToUpdate.getUuid());

            // when: try to rename it second to the name already taken by the default profile
            Executable update = () -> tspService.updateTspProfile(profileToUpdateUuid,
                    aTspProfileRequest()
                            .withName(duplicitName)
                            .build());

            // then
            assertThrows(AlreadyExistException.class, update);
        }

        @Test
        void update_keepingSameName_succeeds()
                throws AlreadyExistException, AttributeException, NotFoundException {
            // when
            TspProfileDto dto = tspService.updateTspProfile(
                    SecuredUUID.fromString(defaultTspProfile.getUuid()),
                    aTspProfileRequest()
                            .withName(defaultTspProfile.getName())
                            .withDescription("updated description")
                            .build());

            // then
            assertEquals(defaultTspProfile.getName(), dto.getName());
            assertEquals("updated description", dto.getDescription());
        }
    }
}
