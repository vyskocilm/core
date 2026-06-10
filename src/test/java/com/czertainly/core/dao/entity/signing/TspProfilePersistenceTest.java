package com.czertainly.core.dao.entity.signing;

import com.otilm.api.model.core.signing.TspAuthenticationMethod;
import com.czertainly.core.dao.repository.signing.TspProfileRepository;
import com.czertainly.core.util.BaseSpringBootTest;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

class TspProfilePersistenceTest extends BaseSpringBootTest {

    @Autowired
    private TspProfileRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void defaultAllowedAuthenticationMethodsRoundTrip() {
        TspProfile profile = new TspProfile();
        profile.setName("default-methods-profile");
        TspProfile saved = repository.save(profile);
        entityManager.flush();
        entityManager.clear();

        TspProfile reloaded = repository.findById(saved.getUuid()).orElseThrow();
        Assertions.assertNotNull(reloaded.getAllowedAuthenticationMethods());
        Assertions.assertTrue(reloaded.getAllowedAuthenticationMethods().isEmpty());
    }

    @Test
    void persistsAndReadsAllowedAuthenticationMethods() {
        TspProfile profile = new TspProfile();
        profile.setName("methods-profile");
        profile.setAllowedAuthenticationMethods(List.of(
                TspAuthenticationMethod.CLIENT_CERTIFICATE, TspAuthenticationMethod.BASIC_PASSWORD));
        TspProfile saved = repository.save(profile);

        TspProfile reloaded = repository.findById(saved.getUuid()).orElseThrow();
        Assertions.assertEquals(2, reloaded.getAllowedAuthenticationMethods().size());
        Assertions.assertTrue(reloaded.getAllowedAuthenticationMethods().contains(TspAuthenticationMethod.BASIC_PASSWORD));
    }
}
