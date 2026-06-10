package com.czertainly.core.dao.entity.signing;

import com.czertainly.core.dao.repository.signing.TspProfileRepository;
import com.czertainly.core.util.BaseSpringBootTest;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

class TspProfileBasicCredentialPersistenceTest extends BaseSpringBootTest {

    @Autowired
    private TspProfileRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void cascadePersistAndOrphanRemoval() {
        TspProfile profile = new TspProfile();
        profile.setName("cred-profile");

        TspProfileBasicCredential cred = new TspProfileBasicCredential();
        cred.setUsername("svc");
        cred.setSecretUuid(UUID.randomUUID());
        cred.setMappedUserUuid(UUID.randomUUID());
        cred.setTspProfile(profile);
        profile.getBasicCredentials().add(cred);

        TspProfile saved = repository.save(profile);
        // flush + clear so the reload crosses the persistence-context boundary and reflects the DB, not the L1 cache
        entityManager.flush();
        entityManager.clear();
        Assertions.assertEquals(1, repository.findById(saved.getUuid()).orElseThrow().getBasicCredentials().size());

        // orphan removal — clear the collection and confirm the child row is actually deleted from the DB
        TspProfile reloaded = repository.findById(saved.getUuid()).orElseThrow();
        reloaded.getBasicCredentials().clear();
        repository.save(reloaded);
        entityManager.flush();
        entityManager.clear();
        Assertions.assertTrue(repository.findById(saved.getUuid()).orElseThrow().getBasicCredentials().isEmpty());
    }
}
