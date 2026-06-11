package com.czertainly.core.dao.entity.signing;

import com.czertainly.core.dao.repository.signing.TspProfileRepository;
import com.czertainly.core.util.BaseSpringBootTest;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TspProfileBasicCredentialPersistenceTest extends BaseSpringBootTest {

    @Autowired
    private TspProfileRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void cascadesPersist_andRemovesOrphans() {
        // given
        TspProfile profile = new TspProfile();
        profile.setName("cred-profile");

        TspProfileBasicCredential cred = new TspProfileBasicCredential();
        cred.setUsername("svc");
        cred.setSecretUuid(UUID.randomUUID());
        cred.setMappedUserUuid(UUID.randomUUID());
        cred.setTspProfile(profile);
        profile.getBasicCredentials().add(cred);

        // when — cascade persist; flush + clear so the reload crosses the persistence-context boundary
        TspProfile saved = repository.save(profile);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(repository.findById(saved.getUuid()).orElseThrow().getBasicCredentials()).hasSize(1);

        // when — orphan removal: clear the collection
        TspProfile reloaded = repository.findById(saved.getUuid()).orElseThrow();
        reloaded.getBasicCredentials().clear();
        repository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // then — the child row is actually deleted from the DB
        assertThat(repository.findById(saved.getUuid()).orElseThrow().getBasicCredentials()).isEmpty();
    }
}
