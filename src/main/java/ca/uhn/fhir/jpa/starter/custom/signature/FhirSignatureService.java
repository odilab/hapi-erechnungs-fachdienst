package ca.uhn.fhir.jpa.starter.custom.signature;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Signature;
import org.hl7.fhir.r4.model.Identifier;

import java.util.Date;

public class FhirSignatureService {

    private static final String SIGNATURE_EXTENSION_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-docref-signature";
    private static final String SIGNATURE_TYPE_SYSTEM = "urn:iso-astm:E1762-95:2013";
    private static final String SIGNATURE_TYPE_CODE = "1.2.840.10065.1.12.1.1";
    private static final String SIGNATURE_TYPE_DISPLAY = "Author's Signature";

    /**
     * Fügt eine CAdES-Signatur als Extension zu einem DocumentReference hinzu
     * 
     * @param docRef Das DocumentReference, das die Signatur erhalten soll
     * @param cadesSignature Die CAdES-Signatur als Byte-Array
     * @return Das aktualisierte DocumentReference mit Signatur-Extension
     */
    public DocumentReference attachCadesSignature(DocumentReference docRef, byte[] cadesSignature) {
        // Entferne existierende Signatur-Extension falls vorhanden
        docRef.getExtension().removeIf(ext -> SIGNATURE_EXTENSION_URL.equals(ext.getUrl()));

        // FHIR Signature erstellen
        Signature signature = new Signature();

        // Signaturtyp setzen (CAdES)
        Coding coding = new Coding()
                .setSystem(SIGNATURE_TYPE_SYSTEM)
                .setCode(SIGNATURE_TYPE_CODE)
                .setDisplay(SIGNATURE_TYPE_DISPLAY);
        signature.addType(coding);

        // Wer hat signiert (Fachdienst)
        Reference whoRef = new Reference();
        Identifier identifier = new Identifier()
            .setSystem("https://gematik.de/fhir/sid/telematik-id")
            .setValue("ERechnungFachdienst");
        whoRef.setIdentifier(identifier)
            .setDisplay("E-Rechnung Fachdienst");
        signature.setWho(whoRef);

        // Zeitstempel der Signatur
        signature.setWhen(new Date());

        // Signaturdaten direkt als Byte-Array
        signature.setData(cadesSignature);

        // Extension erstellen und an DocumentReference anhängen
        Extension signatureExtension = new Extension(SIGNATURE_EXTENSION_URL, signature);
        docRef.addExtension(signatureExtension);

        return docRef;
    }
} 