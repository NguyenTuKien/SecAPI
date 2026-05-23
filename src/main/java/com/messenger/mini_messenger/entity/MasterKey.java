package com.messenger.mini_messenger.entity;

import com.messenger.mini_messenger.enums.MasterKeyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "master_keys")
public class MasterKey extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "public_key", columnDefinition = "text", nullable = false)
    private String publicKey;

    @Column(name = "encrypted_private_key", columnDefinition = "text", nullable = false)
    private String encryptedPrivateKey;

    @Column(name = "private_key_iv", length = 128, nullable = false)
    private String privateKeyIv;

    @Column(name = "pin_salt", length = 128, nullable = false)
    private String pinSalt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kdf_params", columnDefinition = "json", nullable = false)
    private Map<String, Object> kdfParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MasterKeyStatus status = MasterKeyStatus.ACTIVE;

    @Column(name = "rotated_at")
    private Instant rotatedAt;
}
