package ca.uhn.fhir.jpa.starter.custom.signature;

import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class SignatureService {
    
    public SignatureService() {
        // Bouncy Castle Provider registrieren
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    /**
     * Signiert PDF- und FHIR-Daten mit CAdES (RSASSA-PSS)
     * 
     * @param pdfData PDF-Dokument als Byte-Array
     * @param fhirData FHIR-Dokument als Byte-Array
     * @param certificate Signaturzertifikat
     * @param privateKey Private Key für die Signatur
     * @return CAdES-Signatur als Byte-Array
     */
    public byte[] signPdfAndFhir(byte[] pdfData, byte[] fhirData, X509Certificate certificate, PrivateKey privateKey) throws Exception {
        if (pdfData == null || pdfData.length == 0 || fhirData == null || fhirData.length == 0) {
            throw new IllegalArgumentException("PDF und FHIR Daten dürfen nicht leer sein");
        }

        // PDF und FHIR Base64-kodieren
        byte[] pdfBase64 = Base64.getEncoder().encode(pdfData);
        byte[] fhirBase64 = Base64.getEncoder().encode(fhirData);
        
        // Konkatenieren (PDF zuerst, dann FHIR)
        byte[] concatenatedData = new byte[pdfBase64.length + fhirBase64.length];
        System.arraycopy(pdfBase64, 0, concatenatedData, 0, pdfBase64.length);
        System.arraycopy(fhirBase64, 0, concatenatedData, pdfBase64.length, fhirBase64.length);

        // CAdES-Signatur erstellen
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        
        // RSASSA-PSS mit SHA-256
        JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSAandMGF1");
        csBuilder.setProvider("BC");
        
        // SignerInfo konfigurieren
        JcaSignerInfoGeneratorBuilder signerInfoBuilder = new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        );
        
        generator.addSignerInfoGenerator(
            signerInfoBuilder.build(csBuilder.build(privateKey), certificate)
        );

        // Zertifikate zur Signatur hinzufügen
        List<X509Certificate> certList = new ArrayList<>();
        certList.add(certificate);
        JcaCertStore certs = new JcaCertStore(certList);
        generator.addCertificates(certs);

        // Signatur generieren (attached = true)
        CMSProcessableByteArray cmsData = new CMSProcessableByteArray(concatenatedData);
        CMSSignedData signedData = generator.generate(cmsData, true);

        return signedData.getEncoded();
    }
} 