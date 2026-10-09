package com.paultinius.k8s.dashboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Encrypts secrets (the LDAP bind password) before they are written to disk
 * under the dashboard's data directory, using a key generated on first use
 * and kept next to the data it protects.
 *
 * <p>This defends the on-disk settings file against being read by anything
 * other than this process and whoever can read the key file - the same
 * trust boundary already relied on for saved kubeconfigs. It does not
 * defend against someone with access to the running process or the key
 * file itself; there is no external secret manager here.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey key;

    public SecretCipher(DashboardProperties properties) {
        Path dataDir = DataDirs.resolve(properties);
        this.key = loadOrCreateKey(dataDir.resolve(".secret.key"));
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt the secret", exception);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length <= GCM_IV_BYTES) {
                return "";
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ciphertext = new byte[raw.length - GCM_IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(raw, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            log.warn("Could not decrypt a stored secret - it was likely written with a different key");
            return "";
        }
    }

    private static SecretKey loadOrCreateKey(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                byte[] decoded = Base64.getDecoder().decode(Files.readString(path).trim());
                return new SecretKeySpec(decoded, "AES");
            }
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_BITS);
            SecretKey generated = generator.generateKey();
            Files.createDirectories(path.getParent());
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getEncoded()));
            try {
                Files.setPosixFilePermissions(path, Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ));
            } catch (UnsupportedOperationException ignored) {
                // Windows has no POSIX modes.
            }
            return generated;
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Could not prepare the secret encryption key at " + path, exception);
        }
    }
}
