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

import static org.junit.jupiter.api.Assertions.*;

public class SubmitOperationProviderTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmitOperationProviderTest.class);

    private Parameters baseInParams;

    @BeforeEach
    void setUpParameters() {
        baseInParams = new Parameters();
        assertNotNull(testRechnungDocRef, "TestRechnungDocRef muss vor den Tests initialisiert sein.");
        baseInParams.addParameter().setName("rechnung").setResource(testRechnungDocRef);
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

        // Führe die Operation aus (erwarte keinen Fehler für gültige Eingabe im Normalmodus)
        Parameters outParams = assertDoesNotThrow(() -> {
            return client.operation()
                .onInstance(testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(inParams)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        }, "Operation sollte im Normalmodus mit gültigen Daten nicht fehlschlagen.");

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

        // Zusätzliche Prüfungen für die transformierte Rechnung
        assertTrue(transformedDocRef.hasId(), "Die transformierte Rechnung muss eine ID haben.");
        assertTrue(transformedDocRef.hasRelatesTo(), "Die transformierte Rechnung muss ein relatesTo-Element haben.");
        assertEquals(1, transformedDocRef.getRelatesTo().size(), "Es sollte genau ein relatesTo-Element geben.");
        DocumentReference.DocumentReferenceRelatesToComponent relatesTo = transformedDocRef.getRelatesToFirstRep();
        assertEquals(DocumentReference.DocumentRelationshipType.TRANSFORMS, relatesTo.getCode(), "Der relatesTo-Code sollte TRANSFORMS sein.");
        assertTrue(relatesTo.hasTarget(), "relatesTo muss ein Ziel (target) haben.");
        assertTrue(relatesTo.getTarget().hasReference(), "Das relatesTo-Ziel muss eine Referenz enthalten.");
        
        String originalDocRefReference = relatesTo.getTarget().getReference();
        LOGGER.info("Referenz auf die ursprüngliche Rechnung: {}", originalDocRefReference);

        // Lade die ursprüngliche DocumentReference vom Server
        DocumentReference originalDocRef = client.read()
			  .resource(DocumentReference.class)
			  .withUrl(originalDocRefReference)
			  .withAdditionalHeader("Authorization", authHeader)
			  .execute();
        
        assertNotNull(originalDocRef, "Die ursprüngliche DocumentReference konnte nicht geladen werden.");
        LOGGER.info("Ursprüngliche DocumentReference erfolgreich geladen mit ID: {}", originalDocRef.getIdElement().getValue());

        // --- Vergleiche Felder zwischen originalDocRef und transformedDocRef --- 

        // IDs MÜSSEN unterschiedlich sein
        assertNotEquals(originalDocRef.getIdElement().getIdPart(), transformedDocRef.getIdElement().getIdPart(),
                        "Die ID der originalen und der transformierten Rechnung dürfen nicht gleich sein.");

        // relatesTo darf im Original nicht vorhanden sein (oder leer)
        assertFalse(originalDocRef.hasRelatesTo(), "Die originale Rechnung sollte kein relatesTo-Element haben.");

        // Vergleiche Status
        assertEquals(originalDocRef.getStatus(), transformedDocRef.getStatus(), "Status sollte übereinstimmen.");

        // Vergleiche Typ
        // Beachte: CodeableConcept Vergleich braucht ggf. eine tiefere Prüfung
        assertTrue(originalDocRef.hasType() && transformedDocRef.hasType(), "Beide sollten einen Typ haben.");
        // Einfacher Vergleich der Codes im ersten Coding (Annahme: nur eins relevant)
        if (originalDocRef.getType().hasCoding() && !originalDocRef.getType().getCoding().isEmpty() &&
            transformedDocRef.getType().hasCoding() && !transformedDocRef.getType().getCoding().isEmpty()) {
            assertEquals(originalDocRef.getType().getCodingFirstRep().getSystem(), transformedDocRef.getType().getCodingFirstRep().getSystem(), "Typ Coding System sollte übereinstimmen.");
            assertEquals(originalDocRef.getType().getCodingFirstRep().getCode(), transformedDocRef.getType().getCodingFirstRep().getCode(), "Typ Coding Code sollte übereinstimmen.");
        }

        // Vergleiche Subject
        assertTrue(originalDocRef.hasSubject() && transformedDocRef.hasSubject(), "Beide sollten ein Subject haben.");
        assertEquals(originalDocRef.getSubject().getReference(), transformedDocRef.getSubject().getReference(), "Subject Referenz sollte übereinstimmen.");
        
        // Vergleiche Content (Annahme: nur ein Content-Element)
        assertTrue(originalDocRef.hasContent() && !originalDocRef.getContent().isEmpty(), "Original sollte Content haben.");
        assertTrue(transformedDocRef.hasContent() && !transformedDocRef.getContent().isEmpty(), "Transformierte sollte Content haben.");
        assertEquals(originalDocRef.getContent().size(), transformedDocRef.getContent().size(), "Anzahl der Content-Elemente sollte übereinstimmen.");
        
        DocumentReference.DocumentReferenceContentComponent originalContent = originalDocRef.getContentFirstRep();
        DocumentReference.DocumentReferenceContentComponent transformedContent = transformedDocRef.getContentFirstRep();
        assertTrue(originalContent.hasAttachment() && transformedContent.hasAttachment(), "Beide sollten einen Attachment im Content haben.");
        assertEquals(originalContent.getAttachment().getContentType(), transformedContent.getAttachment().getContentType(), "Attachment ContentType sollte übereinstimmen.");
        // Vorsicht beim Datenvergleich - kann groß sein. Nur auf Existenz prüfen?
        assertTrue(originalContent.getAttachment().hasData() && transformedContent.getAttachment().hasData(), "Beide Attachments sollten Daten enthalten.");
        assertArrayEquals(originalContent.getAttachment().getData(), transformedContent.getAttachment().getData(), "Attachment Daten sollten übereinstimmen.");

        // Weitere Felder könnten hier nach Bedarf verglichen werden (z.B. author, custodian, context...)

        LOGGER.info("Vergleich zwischen originaler und transformierter Rechnung erfolgreich abgeschlossen.");
    }
}
