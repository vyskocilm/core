package com.otilm.core.dao.entity.signing;

import com.otilm.api.model.core.signing.TspAuthenticationMethod;
import com.otilm.core.dao.repository.signing.TspProfileRepository;
import com.otilm.core.util.BaseSpringBootTest;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TspProfilePersistenceTest extends BaseSpringBootTest {

    @Autowired
    private TspProfileRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void defaultsToEmptyAllowedAuthenticationMethods_whenNoneSet() {
        // given
        TspProfile profile = new TspProfile();
        profile.setName("default-methods-profile");
        TspProfile saved = repository.save(profile);
        entityManager.flush();
        entityManager.clear();

        // when
        TspProfile reloaded = repository.findById(saved.getUuid()).orElseThrow();

        // then
        assertThat(reloaded.getAllowedAuthenticationMethods()).isNotNull();
        assertThat(reloaded.getAllowedAuthenticationMethods()).isEmpty();
    }

    @Test
    void persistsAndReadsAllowedAuthenticationMethods() {
        // given
        TspProfile profile = new TspProfile();
        profile.setName("methods-profile");
        profile.setAllowedAuthenticationMethods(List.of(
                TspAuthenticationMethod.CLIENT_CERTIFICATE, TspAuthenticationMethod.BASIC_PASSWORD));
        TspProfile saved = repository.save(profile);

        // when
        TspProfile reloaded = repository.findById(saved.getUuid()).orElseThrow();

        // then
        assertThat(reloaded.getAllowedAuthenticationMethods()).hasSize(2);
        assertThat(reloaded.getAllowedAuthenticationMethods()).contains(TspAuthenticationMethod.BASIC_PASSWORD);
    }
}
