package com.otilm.core.model.signing;

import com.otilm.api.model.core.signing.TspAuthenticationMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class TspProfileModelTest {

    @Test
    void carriesMethodsAndCredentialRefs() {
        var cred = new TspProfileModel.BasicCredentialRef("svc", UUID.randomUUID(), UUID.randomUUID(), "fp");
        TspProfileModel model = new TspProfileModel(
                UUID.randomUUID(), "p", null, true, null, null, List.of(),
                List.of(TspAuthenticationMethod.BASIC_PASSWORD), List.of(cred), UUID.randomUUID());

        Assertions.assertTrue(model.allowedAuthenticationMethods().contains(TspAuthenticationMethod.BASIC_PASSWORD));
        Assertions.assertEquals("svc", model.basicCredentials().get(0).username());
    }
}
