package com.paultinius.k8s.dashboard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherTest {

    @TempDir
    Path tempDir;

    private SecretCipher cipher() {
        DashboardProperties properties = new DashboardProperties();
        properties.getCluster().setDataDir(tempDir.toString());
        return new SecretCipher(properties);
    }

    @Test
    void encrypt_thenDecrypt_roundTrips() {
        SecretCipher cipher = cipher();

        String encrypted = cipher.encrypt("s3cret-bind-password");

        assertThat(encrypted).isNotBlank().isNotEqualTo("s3cret-bind-password");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("s3cret-bind-password");
    }

    @Test
    void encrypt_blankOrNull_returnsBlank() {
        SecretCipher cipher = cipher();

        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.encrypt(null)).isEmpty();
        assertThat(cipher.decrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isEmpty();
    }

    @Test
    void decrypt_garbage_returnsEmptyInsteadOfThrowing() {
        SecretCipher cipher = cipher();

        assertThat(cipher.decrypt("not valid base64 ciphertext")).isEmpty();
    }

    @Test
    void keyFile_isPersistedAndReusedAcrossInstances() {
        SecretCipher first = cipher();
        String encrypted = first.encrypt("s3cret-bind-password");

        SecretCipher second = cipher();

        assertThat(second.decrypt(encrypted)).isEqualTo("s3cret-bind-password");
    }
}
