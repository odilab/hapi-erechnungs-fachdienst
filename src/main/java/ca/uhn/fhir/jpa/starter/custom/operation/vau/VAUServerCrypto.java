package ca.uhn.fhir.jpa.starter.custom.operation.vau;

import org.bouncycastle.crypto.DerivationParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;

@Component
public class VAUServerCrypto {

    private static final byte[] INFO_ECIS_VAU_TRANSPORT = "ecies-vau-transport".getBytes();
    private static final int IV_LENGTH = 12;
    private static final int AUTHENTICATION_TAG_BITS = 16 * 8;
    private final PrivateKey serverPrivateKey;
    private final PublicKey serverPublicKey;

    public VAUServerCrypto() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        this.serverPrivateKey = loadServerPrivateKey();
        this.serverPublicKey = loadServerPublicKey();
    }

    public PublicKey getPublicKey() {
        return serverPublicKey;
    }

    public String decryptRequest(byte[] encryptedRequest) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(encryptedRequest);
        
        // Read version byte
        byte version = buffer.get();
        if (version != 0x01) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        // Read ephemeral public key coordinates
        byte[] xBytes = new byte[32];
        byte[] yBytes = new byte[32];
        buffer.get(xBytes);
        buffer.get(yBytes);
        
        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);

        // Reconstruct ephemeral public key
        ECNamedCurveParameterSpec params = ECNamedCurveTable.getParameterSpec("brainpoolp256r1");
        ECPublicKey ephemeralPublicKey = VAUKeyUtils.createECPublicKey(params, x, y);

        // Generate shared secret
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(serverPrivateKey);
        ka.doPhase(ephemeralPublicKey, true);
        byte[] sharedSecret = ka.generateSecret();

        // Derive key using HKDF
        byte[] derivedKey = deriveKey(sharedSecret);

        // Read IV and ciphertext
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        // Decrypt
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(AUTHENTICATION_TAG_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derivedKey, "AES"), spec);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext);
    }

    public byte[] encryptResponse(String response, Key clientKey) throws Exception {
        if (!(clientKey instanceof SecretKeySpec)) {
            throw new InvalidKeyException("Der Schlüssel muss ein AES-Schlüssel sein");
        }

        // Generate IV
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // Encrypt with AES/GCM
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(AUTHENTICATION_TAG_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, clientKey, spec);
        byte[] ciphertext = cipher.doFinal(response.getBytes());

        // Format output: IV || Ciphertext
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(iv);
        output.write(ciphertext);

        return output.toByteArray();
    }

    private byte[] deriveKey(byte[] sharedSecret) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        byte[] derivedKey = new byte[16];
        DerivationParameters params = new HKDFParameters(sharedSecret, null, INFO_ECIS_VAU_TRANSPORT);
        hkdf.init(params);
        hkdf.generateBytes(derivedKey, 0, derivedKey.length);
        return derivedKey;
    }

    private byte[] pad32(byte[] input) {
        if (input[0] == 0 && input.length > 32) {
            byte[] tmp = new byte[input.length - 1];
            System.arraycopy(input, 1, tmp, 0, tmp.length);
            input = tmp;
        }

        if (input.length < 32) {
            byte[] tmp = new byte[32];
            System.arraycopy(input, 0, tmp, 32 - input.length, input.length);
            input = tmp;
        }

        if (input.length == 32) {
            return input;
        }

        throw new IllegalArgumentException("Must be 32 bytes! But was " + input.length);
    }

    private PrivateKey loadServerPrivateKey() throws Exception {
        // Versuche zuerst ClassPath-Ressource (für Container)
        InputStream keyStream = getClass().getResourceAsStream("/certificates/id_enc/fd_id_enc");
        if (keyStream != null) {
            try (InputStream stream = keyStream) {
                return VAUKeyUtils.loadPrivateKey(stream);
            }
        }
        
        // Fallback auf Dateisystem (für lokale Entwicklung)
        Path keyPath = Paths.get("src/main/resources/certificates/id_enc/fd_id_enc");
        if (Files.exists(keyPath)) {
            return VAUKeyUtils.loadPrivateKey(keyPath);
        }
        
        throw new IOException("VAU privater Schlüssel nicht gefunden (weder in ClassPath noch im Dateisystem)");
    }

    private PublicKey loadServerPublicKey() throws Exception {
        // Versuche zuerst ClassPath-Ressource (für Container)
        InputStream keyStream = getClass().getResourceAsStream("/certificates/id_enc/fd_id_enc.pub");
        if (keyStream != null) {
            try (InputStream stream = keyStream) {
                return VAUKeyUtils.loadPublicKey(stream);
            }
        }
        
        // Fallback auf Dateisystem (für lokale Entwicklung)
        Path certPath = Paths.get("src/main/resources/certificates/id_enc/fd_id_enc.pub");
        if (Files.exists(certPath)) {
            return VAUKeyUtils.loadPublicKey(certPath);
        }
        
        throw new IOException("VAU öffentlicher Schlüssel nicht gefunden (weder in ClassPath noch im Dateisystem)");
    }
} 