package ca.uhn.fhir.jpa.starter.custom.operation.vau;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.math.BigInteger;

public class VAUKeyUtils {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static ECPublicKey createECPublicKey(ECNamedCurveParameterSpec params, BigInteger x, BigInteger y) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        ECPoint point = params.getCurve().createPoint(x, y);
        ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(point, params);
        KeyFactory keyFactory = KeyFactory.getInstance("EC", new BouncyCastleProvider());
        return (ECPublicKey) keyFactory.generatePublic(pubKeySpec);
    }

    public static PrivateKey loadPrivateKey(Path keyPath) throws IOException {
        try (PEMParser pemParser = new PEMParser(new FileReader(keyPath.toFile()))) {
            Object obj = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(new BouncyCastleProvider());

            if (obj instanceof PEMKeyPair) {
                return converter.getPrivateKey(((PEMKeyPair) obj).getPrivateKeyInfo());
            } else if (obj instanceof PrivateKeyInfo) {
                return converter.getPrivateKey((PrivateKeyInfo) obj);
            }
            throw new IOException("Unbekanntes Schlüsselformat");
        } catch (Exception e) {
            throw new IOException("Fehler beim Laden des privaten Schlüssels: " + e.getMessage(), e);
        }
    }

    /**
     * Lädt einen privaten Schlüssel aus einem InputStream (für ClassPath-Ressourcen)
     */
    public static PrivateKey loadPrivateKey(InputStream keyStream) throws IOException {
        try (PEMParser pemParser = new PEMParser(new InputStreamReader(keyStream))) {
            Object obj = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(new BouncyCastleProvider());

            if (obj instanceof PEMKeyPair) {
                return converter.getPrivateKey(((PEMKeyPair) obj).getPrivateKeyInfo());
            } else if (obj instanceof PrivateKeyInfo) {
                return converter.getPrivateKey((PrivateKeyInfo) obj);
            }
            throw new IOException("Unbekanntes Schlüsselformat");
        } catch (Exception e) {
            throw new IOException("Fehler beim Laden des privaten Schlüssels: " + e.getMessage(), e);
        }
    }

    public static PublicKey loadPublicKey(Path keyPath) throws IOException {
        try (PEMParser pemParser = new PEMParser(new FileReader(keyPath.toFile()))) {
            Object obj = pemParser.readObject();
            if (obj == null) {
                throw new IOException("Konnte keinen öffentlichen Schlüssel aus der Datei lesen");
            }
            
            if (!(obj instanceof SubjectPublicKeyInfo)) {
                throw new IOException("Ungültiges Format für öffentlichen Schlüssel");
            }
            
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(new BouncyCastleProvider());
            
            return converter.getPublicKey((SubjectPublicKeyInfo) obj);
        } catch (Exception e) {
            throw new IOException("Fehler beim Laden des öffentlichen Schlüssels: " + e.getMessage(), e);
        }
    }

    /**
     * Lädt einen öffentlichen Schlüssel aus einem InputStream (für ClassPath-Ressourcen)
     */
    public static PublicKey loadPublicKey(InputStream keyStream) throws IOException {
        try (PEMParser pemParser = new PEMParser(new InputStreamReader(keyStream))) {
            Object obj = pemParser.readObject();
            if (obj == null) {
                throw new IOException("Konnte keinen öffentlichen Schlüssel aus dem Stream lesen");
            }
            
            if (!(obj instanceof SubjectPublicKeyInfo)) {
                throw new IOException("Ungültiges Format für öffentlichen Schlüssel");
            }
            
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(new BouncyCastleProvider());
            
            return converter.getPublicKey((SubjectPublicKeyInfo) obj);
        } catch (Exception e) {
            throw new IOException("Fehler beim Laden des öffentlichen Schlüssels: " + e.getMessage(), e);
        }
    }
} 