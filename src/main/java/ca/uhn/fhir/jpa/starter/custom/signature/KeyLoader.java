package ca.uhn.fhir.jpa.starter.custom.signature;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class KeyLoader {

    /**
     * Lädt das Zertifikat und den Private Key aus einer PKCS#12-Datei
     * 
     * @param p12Path Pfad zur PKCS#12-Datei
     * @param password Passwort für die PKCS#12-Datei
     * @return KeyMaterial mit Zertifikat und Private Key
     */
    public static KeyMaterial loadKeyMaterial(String p12Path, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(p12Path)) {
            keyStore.load(fis, password.toCharArray());
        }
        
        // Ersten Alias aus dem Keystore verwenden
        String alias = keyStore.aliases().nextElement();
        
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password.toCharArray());
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);

        return new KeyMaterial(certificate, privateKey);
    }

    public static class KeyMaterial {
        private final X509Certificate certificate;
        private final PrivateKey privateKey;

        public KeyMaterial(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        public X509Certificate getCertificate() {
            return certificate;
        }

        public PrivateKey getPrivateKey() {
            return privateKey;
        }
    }
} 