package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import ca.uhn.fhir.jpa.starter.custom.BaseProviderTest;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

public class SubmitOperationProviderTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmitOperationProviderTest.class);

    private Parameters baseInParams;

    @BeforeEach
    void setUpParameters() {
        baseInParams = new Parameters();
        assertNotNull(testRechnungDocRef, "TestRechnungDocRef muss vor den Tests initialisiert sein.");
        baseInParams.addParameter().setName("rechnung").setResource(testRechnungDocRef);

        // Füge Test-Anhänge hinzu, falls sie initialisiert sind
        if (testAnhangDocRef != null) {
            baseInParams.addParameter().setName("anhang").setResource(testAnhangDocRef);
            LOGGER.debug("TestAnhangDocRef zu baseInParams hinzugefügt.");
        }
        // Wenn es einen zweiten Test-Anhang gibt und dieser verwendet werden soll:
        // if (testAnhangDocRef2 != null) {
        //     baseInParams.addParameter().setName("anhang").setResource(testAnhangDocRef2);
        //     LOGGER.debug("TestAnhangDocRef2 zu baseInParams hinzugefügt.");
        // }

        assertNotNull(testPatient, "TestPatient muss vor den Tests initialisiert sein.");
        assertNotNull(testPatient.getIdElement(), "TestPatient muss ein IdElement haben.");
        assertNotNull(testPatient.getIdElement().getIdPart(), "TestPatient muss eine ID haben.");
        LOGGER.debug("Base Parameters mit 'rechnung' und gültiger TestPatient ID für Test initialisiert.");
    }

    @Test
    void testSubmitOperation_IsReachable() {
        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");
        Parameters outParams = client.operation()
                .onInstance(testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(baseInParams)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        assertNotNull(outParams, "Die Operation sollte eine Antwort zurückgeben (Parameters-Objekt), auch wenn leer.");
    }

    @Test
    void testSubmitOperation_MissingRechnungParameter_ThrowsException() {
        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");
        Parameters inParamsWithoutRechnung = new Parameters();
        assertNotNull(testAnhangDocRef, "TestAnhangDocRef darf nicht null sein.");
        inParamsWithoutRechnung.addParameter().setName("anhang").setResource(testAnhangDocRef);
        inParamsWithoutRechnung.addParameter().setName("modus").setValue(new CodeType("normal"));
        assertThrows(InvalidRequestException.class, () -> {
            client.operation()
                .onInstance(testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(inParamsWithoutRechnung)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        }, "Es sollte eine InvalidRequestException geworfen werden, wenn der Parameter 'rechnung' fehlt.");
        LOGGER.info("Erwartete InvalidRequestException wurde erfolgreich geworfen.");
    }

    @Test
    void testSubmitOperation_AuthorizationScenarios() {
        String egkAuthHeader = "Bearer " + getValidAccessToken("EGK1");
        assertThrows(AuthenticationException.class, () -> {
            client.operation()
                    .onInstance(testPatient.getIdElement())
                    .named("$erechnung-submit")
                    .withParameters(baseInParams)
                    .withAdditionalHeader("Authorization", egkAuthHeader)
                    .execute();
        }, "Sollte AuthenticationException für EGK werfen, da die Profession nicht berechtigt ist.");

        String hbaAuthHeader = "Bearer " + getValidAccessToken("HBA_ARZT");
        assertDoesNotThrow(() -> {
            client.operation()
                    .onInstance(testPatient.getIdElement())
                    .named("$erechnung-submit")
                    .withParameters(baseInParams)
                    .withAdditionalHeader("Authorization", hbaAuthHeader)
                    .execute();
        }, "Sollte KEINE Exception für HBA_ARZT (ARZT_KRANKENHAUS) werfen.");

        String smcbAuthHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");
        assertDoesNotThrow(() -> {
            client.operation()
                    .onInstance(testPatient.getIdElement())
                    .named("$erechnung-submit")
                    .withParameters(baseInParams)
                    .withAdditionalHeader("Authorization", smcbAuthHeader)
                    .execute();
        }, "Sollte KEINE Exception für SMCB_KRANKENHAUS (LEISTUNGSERBRINGER) werfen.");
    }

    @Test
    void testSubmitOperation_InvalidRechnung_ThrowsUnprocessableEntityException() {
        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");

        // Erstelle eine ungültige Version der Rechnung (z.B. Status entfernen)
        DocumentReference invalidRechnung = testRechnungDocRef.copy();
        invalidRechnung.setStatus(null); // Ungültig machen, da Status Pflicht ist

        Parameters inParamsWithInvalidRechnung = new Parameters();
        inParamsWithInvalidRechnung.addParameter().setName("rechnung").setResource(invalidRechnung);

        assertThrows(UnprocessableEntityException.class, () -> {
            client.operation()
                .onInstance(testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(inParamsWithInvalidRechnung)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        }, "Sollte eine UnprocessableEntityException werfen, wenn die Rechnung ungültig ist.");
        LOGGER.info("Erwartete UnprocessableEntityException für ungültige Rechnung wurde erfolgreich geworfen.");
    }

    @Test
    void testSubmitOperation_NormalMode_ReturnsTransformedRechnung() {
        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");

        // Kopiere die Basisparameter, um sie nicht für andere Tests zu ändern
        Parameters inParams = baseInParams.copy(); 
        // Füge den Modus 'normal' hinzu
        inParams.addParameter("modus", new CodeType("normal"));

        // Führe die Operation über die Helfermethode aus
        Parameters outParams = executeSuccessfulSubmitOperation(testPatient.getIdElement(), inParams, authHeader);

        // ---- Begin Logik, die vorher teilweise in der Helfermethode war / angepasst wurde ----
        assertNotNull(outParams, "Die Operation sollte eine Antwort zurückgeben.");

        // Prüfe auf den (optionalen) Warnungen-Parameter
        Parameters.ParametersParameterComponent warningsParam = outParams.getParameter("warnungen");
        if (warningsParam != null) {
             assertNotNull(warningsParam.getResource(), "Wenn der Warnungen-Parameter existiert, sollte er eine Ressource enthalten.");
             assertTrue(warningsParam.getResource() instanceof OperationOutcome, "Warnungen-Ressource sollte ein OperationOutcome sein.");
             LOGGER.info("Warnungen-Parameter gefunden.");
        } else {
             LOGGER.info("Kein Warnungen-Parameter gefunden (was ok ist).");
        }

        // Prüfe auf den MUSS-Parameter transformedRechnung
        Parameters.ParametersParameterComponent transformedParam = outParams.getParameter("transformedRechnung");
        assertNotNull(transformedParam, "Der transformedRechnung-Parameter muss im Normalmodus vorhanden sein.");
        assertNotNull(transformedParam.getResource(), "Der transformedRechnung-Parameter muss eine Ressource enthalten.");
        assertTrue(transformedParam.getResource() instanceof DocumentReference, "Die Ressource von transformedRechnung muss eine DocumentReference sein.");

        DocumentReference transformedDocRef = (DocumentReference) transformedParam.getResource();
        LOGGER.info("TransformedRechnung-Parameter gefunden mit ID: {}", transformedDocRef.hasId() ? transformedDocRef.getIdElement().getValue() : "KEINE ID (Fehler!)");

        // Gib die transformierte Rechnung zur Überprüfung in der Konsole aus
        String transformedJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(transformedDocRef);
        LOGGER.info("--- Inhalt der zurückgegebenen transformedRechnung: ---\n{}", transformedJson);
        LOGGER.info("--- Ende transformedRechnung --- ");

        // Zusätzliche Prüfungen für die transformierte Rechnung
        assertTrue(transformedDocRef.hasId(), "Die transformierte Rechnung muss eine ID haben.");
        assertTrue(transformedDocRef.hasRelatesTo(), "Die transformierte Rechnung muss ein relatesTo-Element haben.");
        
        // Finde das korrekte relatesTo-Element für die Transformation
        DocumentReference.DocumentReferenceRelatesToComponent relatesToTransform = null;
        for (DocumentReference.DocumentReferenceRelatesToComponent rt : transformedDocRef.getRelatesTo()) {
            if (rt.getCode() == DocumentReference.DocumentRelationshipType.TRANSFORMS) {
                relatesToTransform = rt;
                break;
            }
        }
        assertNotNull(relatesToTransform, "Es sollte ein relatesTo-Element mit Code TRANSFORMS geben.");
        assertTrue(relatesToTransform.hasTarget(), "relatesTo TRANSFORMS muss ein Ziel (target) haben.");
        assertTrue(relatesToTransform.getTarget().hasReference(), "Das relatesTo TRANSFORMS Ziel muss eine Referenz enthalten.");
        
        String originalDocRefReference = relatesToTransform.getTarget().getReference();
        LOGGER.info("Referenz auf die ursprüngliche Rechnung: {}", originalDocRefReference);

        // Lade die ursprüngliche DocumentReference vom Server
        DocumentReference originalDocRef = client.read()
			  .resource(DocumentReference.class)
			  .withUrl(originalDocRefReference)
			  .withAdditionalHeader("Authorization", authHeader)
			  .execute();
        
        assertNotNull(originalDocRef, "Die ursprüngliche DocumentReference konnte nicht geladen werden.");
        LOGGER.info("Ursprüngliche DocumentReference erfolgreich geladen mit ID: {}", originalDocRef.getIdElement().getValue());
        // ---- Ende Logik, die vorher teilweise in der Helfermethode war / angepasst wurde ----


        // --- Vergleiche Felder zwischen originalDocRef und transformedDocRef --- 

        // IDs MÜSSEN unterschiedlich sein
        assertNotEquals(originalDocRef.getIdElement().getIdPart(), transformedDocRef.getIdElement().getIdPart(),
                        "Die ID der originalen und der transformierten Rechnung dürfen nicht gleich sein.");

        // relatesTo TRANSFORMS darf im Original nicht vorhanden sein (oder zumindest nicht auf sich selbst)
        assertFalse(originalDocRef.getRelatesTo().stream()
            .anyMatch(rt -> rt.getCode() == DocumentReference.DocumentRelationshipType.TRANSFORMS && 
                           rt.hasTarget() && rt.getTarget().getReference().equals(originalDocRef.getIdElement().toVersionless().getValue())),
            "Die originale Rechnung sollte kein TRANSFORMS relatesTo-Element auf sich selbst haben.");


        // Vergleiche Status
        assertEquals(originalDocRef.getStatus(), transformedDocRef.getStatus(), "Status sollte übereinstimmen.");

        // Vergleiche Typ
        assertTrue(originalDocRef.hasType() && transformedDocRef.hasType(), "Beide sollten einen Typ haben.");
        if (originalDocRef.getType().hasCoding() && !originalDocRef.getType().getCoding().isEmpty() &&
            transformedDocRef.getType().hasCoding() && !transformedDocRef.getType().getCoding().isEmpty()) {
            assertEquals(originalDocRef.getType().getCodingFirstRep().getSystem(), transformedDocRef.getType().getCodingFirstRep().getSystem(), "Typ Coding System sollte übereinstimmen.");
            assertEquals(originalDocRef.getType().getCodingFirstRep().getCode(), transformedDocRef.getType().getCodingFirstRep().getCode(), "Typ Coding Code sollte übereinstimmen.");
        }

        // Vergleiche Subject
        assertTrue(originalDocRef.hasSubject() && transformedDocRef.hasSubject(), "Beide sollten ein Subject haben.");
        assertEquals(originalDocRef.getSubject().getReference(), transformedDocRef.getSubject().getReference(), "Subject Referenz sollte übereinstimmen.");
        
        // Vergleiche Content (Annahme: nur ein Content-Element für die Hauptrechnung, weitere könnten Anhänge sein)
        // Wir suchen das Content-Element, das nicht die URL zu einer Binary ist (also die ursprüngliche Invoice)
        DocumentReference.DocumentReferenceContentComponent originalInvoiceContent = originalDocRef.getContent().stream()
            .filter(c -> c.hasAttachment() && c.getAttachment().hasData())
            .findFirst()
            .orElse(null);
        assertNotNull(originalInvoiceContent, "Original sollte ein Content-Element mit Daten (Invoice) haben.");

        // In der transformierten Rechnung suchen wir das Content-Element, das zur ursprünglichen Invoice passt (gleicher ContentType)
        // und jetzt eine URL hat.
        DocumentReference.DocumentReferenceContentComponent transformedInvoiceContent = transformedDocRef.getContent().stream()
            .filter(c -> c.hasAttachment() && 
                         c.getAttachment().hasUrl() && // Muss eine URL haben
                         c.getAttachment().getContentType().equals(originalInvoiceContent.getAttachment().getContentType()) &&
                         !c.getAttachment().getUrl().startsWith("Binary/")) // Unterscheidung von PDF-Binary
            .findFirst()
            .orElse(null);
         // Wenn die obige Logik nicht greift (weil z.B. nur ein PDF als Content da ist), nehmen wir erstmal das erste.
        if (transformedInvoiceContent == null && transformedDocRef.hasContent()) {
            transformedInvoiceContent = transformedDocRef.getContentFirstRep();
        }
        assertNotNull(transformedInvoiceContent, "Transformierte sollte ein passendes Content-Element (Invoice) haben.");
        
        assertEquals(originalInvoiceContent.getAttachment().getContentType(), transformedInvoiceContent.getAttachment().getContentType(), "Attachment ContentType der Invoice sollte übereinstimmen.");
        
        // --- Angepasste Prüfung für Daten und URL für die Invoice --- 
        assertTrue(originalInvoiceContent.getAttachment().hasData(), "Das originale Invoice-Attachment sollte Daten enthalten.");
        assertFalse(transformedInvoiceContent.getAttachment().hasData(), "Das transformierte Invoice-Attachment sollte KEINE Daten mehr enthalten.");
        assertTrue(transformedInvoiceContent.getAttachment().hasUrl(), "Das transformierte Invoice-Attachment sollte eine URL enthalten.");
        assertNotNull(transformedInvoiceContent.getAttachment().getUrl(), "Die URL im transformierten Invoice-Attachment darf nicht null sein.");
        
        // --- Spezifische Prüfung für PDF, falls es als separates Content-Element existiert ---
        DocumentReference.DocumentReferenceContentComponent originalPdfContent = originalDocRef.getContent().stream()
            .filter(c -> c.hasAttachment() && "application/pdf".equals(c.getAttachment().getContentType()) && c.getAttachment().hasData())
            .findFirst()
            .orElse(null);

        DocumentReference.DocumentReferenceContentComponent transformedPdfContent = transformedDocRef.getContent().stream()
            .filter(c -> c.hasAttachment() && "application/pdf".equals(c.getAttachment().getContentType()) && c.getAttachment().hasUrl())
            .findFirst()
            .orElse(null);

        if (originalPdfContent != null && transformedPdfContent != null) {
            LOGGER.info("PDF Content gefunden, prüfe PDF spezifisch.");
            assertTrue(originalPdfContent.getAttachment().hasData(), "Das originale PDF-Attachment sollte Daten enthalten.");
            assertFalse(transformedPdfContent.getAttachment().hasData(), "Das transformierte PDF-Attachment sollte KEINE Daten mehr enthalten.");
            assertTrue(transformedPdfContent.getAttachment().hasUrl(), "Das transformierte PDF-Attachment sollte eine URL enthalten.");
            String pdfBinaryUrl = transformedPdfContent.getAttachment().getUrl();
            assertNotNull(pdfBinaryUrl, "Die URL im transformierten PDF-Attachment darf nicht null sein.");
            LOGGER.info("Prüfung von PDF data/url erfolgreich: Original hat data, Transformierte hat url ({})", pdfBinaryUrl);

            // --- Hole die Binary Ressource und speichere die PDF --- 
            savePdfFromBinaryUrl(pdfBinaryUrl, "enriched_rechnung_normal_mode.pdf", authHeader);
        } else {
            LOGGER.warn("Kein separates PDF Content-Element zum Speichern in originaler UND transformierter Rechnung gefunden oder eines fehlt. Überspringe PDF-Speicherungstest für Hauptrechnung.");
             // Fallback: Wenn nur ein Content-Element da ist und es PDF ist
            if (originalDocRef.getContent().size() == 1 && "application/pdf".equals(originalDocRef.getContentFirstRep().getAttachment().getContentType()) &&
                transformedDocRef.getContent().size() == 1 && "application/pdf".equals(transformedDocRef.getContentFirstRep().getAttachment().getContentType())) {
                 String pdfBinaryUrl = transformedDocRef.getContentFirstRep().getAttachment().getUrl();
                 savePdfFromBinaryUrl(pdfBinaryUrl, "enriched_rechnung_normal_mode_single_content.pdf", authHeader);
            }
        }
        
        LOGGER.info("Vergleich zwischen originaler und transformierter Rechnung erfolgreich abgeschlossen.");
    }

    @Test
    void testSubmitOperation_WithAttachment_VerifyStoredAttachmentContent() {
        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");
        Parameters inParams = baseInParams.copy(); // baseInParams enthält bereits testAnhangDocRef
        inParams.addParameter("modus", new CodeType("normal"));

        // 1. Führe die Operation erfolgreich aus
        Parameters outParams = executeSuccessfulSubmitOperation(testPatient.getIdElement(), inParams, authHeader);

        // 2. Extrahiere die transformierte Hauptrechnung
        Parameters.ParametersParameterComponent transformedParam = outParams.getParameter("transformedRechnung");
        assertNotNull(transformedParam, "Der transformedRechnung-Parameter muss vorhanden sein.");
        DocumentReference transformedHauptRechnung = (DocumentReference) transformedParam.getResource();
        assertNotNull(transformedHauptRechnung, "Die transformierte Hauptrechnung darf nicht null sein.");
        LOGGER.info("TransformedHauptRechnung ID: {}", transformedHauptRechnung.getIdElement().getIdPart());

        // 3. Finde die Referenz zur Anhang-DocumentReference im context.related
        assertTrue(transformedHauptRechnung.hasContext(), "TransformedHauptRechnung sollte einen Context haben.");
        assertTrue(transformedHauptRechnung.getContext().hasRelated(), "Context sollte related Einträge haben.");

        String anhangDocRefId = null;
        for (Reference relatedRef : transformedHauptRechnung.getContext().getRelated()) {
            if ("DocumentReference".equals(relatedRef.getType()) && relatedRef.hasReference()) {
                // Annahme: Die erste gefundene DocumentReference-Referenz ist der gesuchte Anhang.
                // Wenn mehrere Anhänge möglich sind, muss dies ggf. präziser gesucht werden.
                anhangDocRefId = relatedRef.getReference();
                break;
            }
        }
        assertNotNull(anhangDocRefId, "Keine Referenz zu einer Anhang-DocumentReference im context.related gefunden.");
        LOGGER.info("Referenz zur Anhang-DocumentReference gefunden: {}", anhangDocRefId);

        // 4. Lade die Anhang-DocumentReference vom Server
        DocumentReference anhangDocRefVomServer = client.read()
            .resource(DocumentReference.class)
            .withUrl(anhangDocRefId)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();
        assertNotNull(anhangDocRefVomServer, "Konnte die Anhang-DocumentReference ('" + anhangDocRefId + "') nicht vom Server laden.");
        LOGGER.info("Anhang-DocumentReference ('{}') erfolgreich vom Server geladen.", anhangDocRefVomServer.getIdElement().getIdPart());

        // 5. Extrahiere die URL zur Binary aus dem Anhang
        assertTrue(anhangDocRefVomServer.hasContent(), "Geladene Anhang-DocumentReference sollte Content haben.");
        DocumentReference.DocumentReferenceContentComponent anhangContent = anhangDocRefVomServer.getContentFirstRep(); // Annahme: erster Content ist relevant
        assertTrue(anhangContent.hasAttachment(), "Anhang-Content sollte ein Attachment haben.");
        assertTrue(anhangContent.getAttachment().hasUrl(), "Anhang-Attachment sollte eine URL zur Binary haben.");
        String anhangBinaryUrl = anhangContent.getAttachment().getUrl();
        assertNotNull(anhangBinaryUrl, "URL zur Anhang-Binary darf nicht null sein.");
        LOGGER.info("URL zur Anhang-Binary: {}", anhangBinaryUrl);

        // 6. Lade die Binary-Ressource (PDF des Anhangs)
        Binary anhangPdfBinary = client.read()
            .resource(Binary.class)
            .withUrl(anhangBinaryUrl)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();
        assertNotNull(anhangPdfBinary, "Konnte die Anhang-Binary ('" + anhangBinaryUrl + "') nicht laden.");
        assertTrue(anhangPdfBinary.hasData(), "Geladene Anhang-Binary sollte Daten enthalten.");
        byte[] gespeicherteAnhangPdfBytes = anhangPdfBinary.getData();

        // 7. Vergleiche mit dem Original-Testanhang
        assertNotNull(testAnhangDocRef, "testAnhangDocRef für den Vergleich darf nicht null sein.");
        assertTrue(testAnhangDocRef.hasContent(), "testAnhangDocRef sollte Content haben.");
        DocumentReference.DocumentReferenceContentComponent originalAnhangContent = testAnhangDocRef.getContentFirstRep();
        assertTrue(originalAnhangContent.hasAttachment(), "Originaler Anhang-Content sollte ein Attachment haben.");
        assertTrue(originalAnhangContent.getAttachment().hasData(), "Originales Anhang-Attachment sollte Daten haben.");
        byte[] originalAnhangPdfBytes = originalAnhangContent.getAttachment().getData();

        assertArrayEquals(originalAnhangPdfBytes, gespeicherteAnhangPdfBytes, "Die Bytes der gespeicherten Anhang-PDF stimmen nicht mit den Originalbytes überein.");
        LOGGER.info("Inhalt der gespeicherten Anhang-PDF wurde erfolgreich mit dem Original-Testanhang verglichen.");

        // Optional: Gespeicherte Anhang-PDF auch in Datei schreiben zum Überprüfen
        savePdfFromBinaryUrl(anhangBinaryUrl, "stored_attachment_content.pdf", authHeader);
    }

    // Ausgelagerte Methode, um PDF von URL zu speichern
    private void savePdfFromBinaryUrl(String binaryUrl, String outputFileName, String authHeader) {
        LOGGER.info("Versuche Binary Ressource von URL {} zu laden...", binaryUrl);
        Binary fetchedBinary;
        Path outputFile = null; // Deklaration hier, damit es im Catch-Block sichtbar ist
        try {
             fetchedBinary = client.read()
                                    .resource(Binary.class)
                                    .withUrl(binaryUrl)
                                    .withAdditionalHeader("Authorization", authHeader)
                                    .execute();
            assertNotNull(fetchedBinary, "Die Binary Ressource konnte nicht von der URL geladen werden: " + binaryUrl);
            assertTrue(fetchedBinary.hasData(), "Die geladene Binary Ressource sollte Daten enthalten.");

            byte[] pdfBytes = fetchedBinary.getData();
            assertNotNull(pdfBytes, "Die Daten der Binary Ressource dürfen nicht null sein.");
            assertTrue(pdfBytes.length > 0, "Die Daten der Binary Ressource dürfen nicht leer sein.");
            LOGGER.info("Binary Ressource erfolgreich geladen, enthält {} Bytes.", pdfBytes.length);

            Path outputDir = Paths.get("src", "test", "resources", "output");
            outputFile = outputDir.resolve(outputFileName); // Zuweisung hier
            Files.createDirectories(outputDir);
            Files.write(outputFile, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOGGER.info("Die angereicherte PDF wurde erfolgreich in {} gespeichert.", outputFile.toAbsolutePath());

        } catch (IOException ioException) {
            String outputPathForLog = (outputFile != null) ? outputFile.toAbsolutePath().toString() : "unbekanntem Pfad";
            LOGGER.error("Fehler beim Schreiben der PDF-Datei nach {}: {}", outputPathForLog, ioException.getMessage(), ioException);
            fail("Konnte die PDF-Datei nicht schreiben: " + ioException.getMessage());
        } catch (Exception e) {
             LOGGER.error("Fehler beim Laden oder Verarbeiten der Binary/PDF von URL {}: {}", binaryUrl, e.getMessage(), e);
             fail("Fehler beim Laden oder Verarbeiten der Binary/PDF von URL " + binaryUrl, e);
        }
    }


    private Parameters executeSuccessfulSubmitOperation(IdType patientId, Parameters inParams, String authHeader) {
        // Führe die Operation aus (erwarte keinen Fehler für gültige Eingabe im Normalmodus)
        // Das assertDoesNotThrow verbleibt hier, da es die erfolgreiche Ausführung sicherstellt.
        Parameters outParams = assertDoesNotThrow(() -> {
            return client.operation()
                .onInstance(patientId)
                .named("$erechnung-submit")
                .withParameters(inParams)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        }, "Operation sollte im Normalmodus mit gültigen Daten nicht fehlschlagen.");

        // Ein grundlegender Check, ob überhaupt Parameter zurückkamen.
        assertNotNull(outParams, "Die Operation sollte eine Antwort (Parameters-Objekt) zurückgeben.");
        return outParams;
    }
}
