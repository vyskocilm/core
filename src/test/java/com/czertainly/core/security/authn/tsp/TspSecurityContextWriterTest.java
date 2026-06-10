package com.czertainly.core.security.authn.tsp;

import com.otilm.api.model.core.logging.enums.AuthMethod;
import com.czertainly.core.security.authn.client.AuthenticationInfo;
import com.czertainly.core.util.AuthHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class TspSecurityContextWriterTest {

    @Mock
    private AuthHelper authHelper;

    private TspSecurityContextWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TspSecurityContextWriter(authHelper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setFromAuthInfo_nullInfo_returnsFalseAndLeavesContextEmpty() {
        assertFalse(writer.setFromAuthInfo(null));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void setFromAuthInfo_anonymousInfo_returnsFalseAndLeavesContextEmpty() {
        assertFalse(writer.setFromAuthInfo(AuthenticationInfo.getAnonymousAuthenticationInfo()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void setFromAuthInfo_validInfo_populatesContext() {
        AuthenticationInfo info = new AuthenticationInfo(AuthMethod.CERTIFICATE, "uuid-1", "alice",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertTrue(writer.setFromAuthInfo(info));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void authenticateAsUser_proxyFails_returnsFalseAndClearsContext() {
        UUID userUuid = UUID.randomUUID();
        doThrow(new RuntimeException("auth service down")).when(authHelper).authenticateAsUser(userUuid);

        assertFalse(writer.authenticateAsUser(userUuid));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void authenticateAsUser_proxySucceeds_returnsTrue() {
        UUID userUuid = UUID.randomUUID();

        assertTrue(writer.authenticateAsUser(userUuid));
    }
}
