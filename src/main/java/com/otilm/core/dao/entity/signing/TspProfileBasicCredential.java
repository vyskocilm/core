package com.otilm.core.dao.entity.signing;

import com.otilm.api.model.client.signing.protocols.tsp.TspBasicCredentialDto;
import com.otilm.api.model.common.NameAndUuidDto;
import com.otilm.core.dao.entity.UniquelyIdentified;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.proxy.HibernateProxy;

import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@ToString
@Entity
@Table(name = "tsp_profile_basic_credential",
        uniqueConstraints = @UniqueConstraint(name = "tsp_profile_basic_credential_username", columnNames = {"tsp_profile_uuid", "username"}))
public class TspProfileBasicCredential extends UniquelyIdentified {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tsp_profile_uuid", insertable = false, updatable = false)
    @ToString.Exclude
    private TspProfile tspProfile;

    @Column(name = "tsp_profile_uuid", nullable = false)
    private UUID tspProfileUuid;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "secret_uuid", nullable = false)
    private UUID secretUuid;

    @Column(name = "mapped_user_uuid", nullable = false)
    private UUID mappedUserUuid;

    public void setTspProfile(TspProfile tspProfile) {
        this.tspProfile = tspProfile;
        this.tspProfileUuid = tspProfile != null ? tspProfile.getUuid() : null;
    }

    @PrePersist
    private void syncTspProfileUuid() {
        if (tspProfileUuid == null && tspProfile != null) {
            tspProfileUuid = tspProfile.getUuid();
        }
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        TspProfileBasicCredential that = (TspProfileBasicCredential) o;
        return getUuid() != null && Objects.equals(getUuid(), that.getUuid());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
