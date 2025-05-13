package ca.uhn.fhir.jpa.starter.custom.operation.erase;

import ca.uhn.fhir.jpa.starter.custom.BaseProviderTest;
import ca.uhn.fhir.rest.server.exceptions.*;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testklasse für den EraseOperationProvider.
 * Diese Tests prüfen die Funktionalität der $erase Operation für DocumentReference-Ressourcen.
 */
class EraseOperationProviderTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(EraseOperationProviderTest.class);
    private DocumentReference testDocumentForErase;
    private String originalDocRefId; // ID der "originalen" DR, die durch submit+transform entsteht
    private List<String> associatedBinaryIds = new ArrayList<>();
    private List<String> associatedInvoiceIds = new ArrayList<>();
    private List<String> relatedAttachmentDocRefIds = new ArrayList<>();

    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        // Dokument einreichen und für Tests vorbereiten
        prepareDocumentForEraseTests();
    }

    private void prepareDocumentForEraseTests() {
        LOGGER.info("EraseOperationProviderTest: Initialisiere Dokument für $erase Tests.");

        assertNotNull(super.testPatient, "testPatient aus BaseProviderTest darf nicht null sein.");
        assertNotNull(super.testRechnungDocRef, "testRechnungDocRef aus BaseProviderTest darf nicht null sein.");

        Parameters submitParams = new Parameters();
        submitParams.addParameter().setName("rechnung").setResource(super.testRechnungDocRef.copy());

        // Füge bewusst einen Anhang hinzu, um dessen Löschung später zu prüfen
        if (super.testAnhangDocRef != null) {
            submitParams.addParameter().setName("anhang").setResource(super.testAnhangDocRef.copy());
        }

        submitParams.addParameter().setName("modus").setValue(new CodeType("normal"));
        submitParams.addParameter().setName("angereichertesPDF").setValue(new BooleanType(true));

        String leistungserbringerAuthHeader = "Bearer " + super.getValidAccessToken("SMCB_KRANKENHAUS");
        Parameters submitResult = null;
        try {
            submitResult = super.client.operation()
                .onInstance(super.testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(submitParams)
                .withAdditionalHeader("Authorization", leistungserbringerAuthHeader)
                .execute();
        } catch (Exception e) {
            LOGGER.error("EraseOperationProviderTest: Fehler beim $erechnung-submit im Setup.", e);
            fail("Fehler beim Einreichen des initialen Dokuments für Erase-Tests: " + e.getMessage());
        }

        assertNotNull(submitResult, "$erechnung-submit sollte eine Antwort zurückgeben.");
        String submittedDocId = ((StringType) submitResult.getParameter("ergToken").getValue()).getValue();
        LOGGER.info("EraseOperationProviderTest: Dokument via $submit eingereicht, ID für Tests: {}", submittedDocId);

        // Dokument mit Versicherten-Token lesen, um die für $erase relevante Instanz zu haben
        String versichertenAuthHeader = "Bearer " + super.getValidAccessToken("EGK1");
        try {
            this.testDocumentForErase = super.client.read()
                .resource(DocumentReference.class)
                .withId(submittedDocId)
                .withAdditionalHeader("Authorization", versichertenAuthHeader)
                .execute();
            assertNotNull(this.testDocumentForErase, "Konnte das via $submit erstellte Dokument (ID: " + submittedDocId + ") nicht lesen.");
            LOGGER.info("EraseOperationProviderTest: Erfolgreicher Read für Test-Dokument mit ID {}. Version: {}", 
                submittedDocId, this.testDocumentForErase.getMeta().getVersionId());

            // IDs der assoziierten Ressourcen sammeln für spätere Prüfung
            collectAssociatedResourceIds(this.testDocumentForErase);

            // ID der durch "transforms" verknüpften originalen DocumentReference sammeln
            if (this.testDocumentForErase.hasRelatesTo()) {
                for (DocumentReference.DocumentReferenceRelatesToComponent relatesTo : this.testDocumentForErase.getRelatesTo()) {
                    if ("transforms".equals(relatesTo.getCode().toCode()) && relatesTo.hasTarget() && relatesTo.getTarget().getReferenceElement().getResourceType().equals("DocumentReference")) {
                        this.originalDocRefId = relatesTo.getTarget().getReferenceElement().getIdPart();
                        LOGGER.info("Original DocumentReference ID (aus relatesTo): {}", this.originalDocRefId);
                        // Auch für die originale DocumentReference die assoziierten Ressourcen sammeln, da diese auch gelöscht werden sollen
                        DocumentReference originalTempDocRef = client.read().resource(DocumentReference.class).withId(this.originalDocRefId).withAdditionalHeader("Authorization", leistungserbringerAuthHeader).execute(); // LE Token zum Lesen
                        collectAssociatedResourceIds(originalTempDocRef);
                        break;
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("EraseOperationProviderTest: Fehler beim Lesen/Vorbereiten des Test-Dokuments (ID: {}).", submittedDocId, e);
            fail("Fehler beim Lesen/Vorbereiten des Test-Dokuments nach $submit: " + e.getMessage());
        }

        // Dokument in den Papierkorb verschieben
        LOGGER.info("EraseOperationProviderTest: Verschiebe Dokument {} in den Papierkorb.", this.testDocumentForErase.getIdElement().getIdPart());
        Parameters changeStatusParams = new Parameters();
        changeStatusParams.addParameter().setName("tag").setValue(new StringType("papierkorb"));
        try {
            super.client.operation()
                .onInstance(this.testDocumentForErase.getIdElement())
                .named("$change-status")
                .withParameters(changeStatusParams)
                .withAdditionalHeader("Authorization", versichertenAuthHeader)
                .execute();
            // Dokument neu lesen, um aktuellen Stand (mit Papierkorb-Tag und neuer Version) zu haben
            this.testDocumentForErase = super.client.read()
                .resource(DocumentReference.class)
                .withId(this.testDocumentForErase.getIdElement().getIdPart())
                .withAdditionalHeader("Authorization", versichertenAuthHeader)
                .execute();
            LOGGER.info("EraseOperationProviderTest: Dokument {} erfolgreich in Papierkorb verschoben. Neue Version: {}", 
                this.testDocumentForErase.getIdElement().getIdPart(), this.testDocumentForErase.getMeta().getVersionId());
        } catch (Exception e) {
            LOGGER.error("EraseOperationProviderTest: Fehler beim Verschieben des Dokuments in den Papierkorb.", e);
            fail("Konnte Dokument nicht in den Papierkorb verschieben: " + e.getMessage());
        }
    }

    private void collectAssociatedResourceIds(DocumentReference docRef) {
        if (docRef == null) return;
        // Assoziierte Binaries und Invoices aus docRef.content
        if (docRef.hasContent()) {
            for (DocumentReference.DocumentReferenceContentComponent content : docRef.getContent()) {
                if (content.hasAttachment() && content.getAttachment().hasUrl()) {
                    String url = content.getAttachment().getUrl();
                    IdType id = new IdType(url);
                    if (id.hasResourceType() && id.hasIdPart()) {
                        if ("Binary".equals(id.getResourceType())) {
                            associatedBinaryIds.add(id.getIdPart());
                            LOGGER.debug("Binary ID {} für spätere Prüfung gesammelt (aus content von DocRef {}).", id.getIdPart(), docRef.getIdPart());
                        } else if ("Invoice".equals(id.getResourceType())) {
                            associatedInvoiceIds.add(id.getIdPart());
                            LOGGER.debug("Invoice ID {} für spätere Prüfung gesammelt (aus content von DocRef {}).", id.getIdPart(), docRef.getIdPart());
                        }
                    }
                }
            }
        }
        // Verlinkte Anhang-DocumentReferences aus docRef.context.related
        if (docRef.hasContext() && docRef.getContext().hasRelated()) {
            for (Reference related : docRef.getContext().getRelated()) {
                if ("DocumentReference".equals(related.getReferenceElement().getResourceType()) && related.getReferenceElement().hasIdPart()) {
                    String relatedDocId = related.getReferenceElement().getIdPart();
                    relatedAttachmentDocRefIds.add(relatedDocId);
                    LOGGER.debug("Anhang DocumentReference ID {} für spätere Prüfung gesammelt (aus context.related von DocRef {}).", relatedDocId, docRef.getIdPart());
                    // Auch die Binaries/Invoices dieses Anhangs sammeln
                    try {
                        DocumentReference anhangDoc = client.read().resource(DocumentReference.class).withId(relatedDocId).withAdditionalHeader("Authorization", "Bearer " + getValidAccessToken("EGK1")).execute(); // Versicherten-Token zum Lesen
                        collectAssociatedResourceIds(anhangDoc); // Rekursiv, falls Anhänge weitere Anhänge hätten (unwahrscheinlich hier)
                    } catch (Exception e) {
                        LOGGER.warn("Konnte Anhang-DocumentReference {} nicht lesen beim Sammeln von IDs: {}", relatedDocId, e.getMessage());
                    }
                }
            }
        }
    }

    @Test
    void testSuccessfulEraseOperation() {
        LOGGER.info("Starte Test: Erfolgreiche $erase Operation.");
        assertNotNull(testDocumentForErase, "Testdokument für $erase darf nicht null sein.");
        String docIdToErase = testDocumentForErase.getIdElement().getIdPart();
        String versichertenAuthHeader = "Bearer " + super.getValidAccessToken("EGK1");

        OperationOutcome result = super.client.operation()
            .onInstance(testDocumentForErase.getIdElement())
            .named("$erase")
            .withNoParameters(Parameters.class) // $erase hat keine Body-Parameter
            .returnResourceType(OperationOutcome.class) // Explizit den Rückgabetyp angeben
            .withAdditionalHeader("Authorization", versichertenAuthHeader)
            .execute();

        assertNotNull(result, "$erase Operation sollte ein OperationOutcome zurückgeben.");
        assertEquals(1, result.getIssue().size(), "Sollte genau eine Issue von Typ INFORMATION haben.");
        assertEquals(OperationOutcome.IssueSeverity.INFORMATION, result.getIssueFirstRep().getSeverity(), "Issue sollte Severity INFORMATION haben.");
        assertTrue(result.getIssueFirstRep().getDiagnostics().contains("erfolgreich gelöscht"), "Diagnosetext sollte Erfolg signalisieren.");

        // Prüfen, ob die Haupt-DocumentReference gelöscht wurde
        assertThrows(ResourceGoneException.class, () -> {
            super.client.read().resource(DocumentReference.class).withId(docIdToErase).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
        }, "Haupt-DocumentReference " + docIdToErase + " sollte nicht mehr existieren.");
        LOGGER.info("Haupt-DocumentReference {} wurde erfolgreich gelöscht.", docIdToErase);

        // Prüfen, ob die originale DocumentReference (aus relatesTo) gelöscht wurde
        if (originalDocRefId != null && !originalDocRefId.isEmpty()) {
            assertThrows(ResourceGoneException.class, () -> {
                super.client.read().resource(DocumentReference.class).withId(originalDocRefId).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
            }, "Originale DocumentReference " + originalDocRefId + " (aus relatesTo) sollte nicht mehr existieren.");
            LOGGER.info("Originale DocumentReference {} (aus relatesTo) wurde erfolgreich gelöscht.", originalDocRefId);
        }

        // Prüfen, ob assoziierte Binaries gelöscht wurden
        for (String binaryId : associatedBinaryIds) {
            final String currentBinaryId = binaryId; // Für Lambda-Ausdruck
            assertThrows(ResourceGoneException.class, () -> {
                super.client.read().resource(Binary.class).withId(currentBinaryId).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
            }, "Binary " + currentBinaryId + " sollte nicht mehr existieren.");
            LOGGER.info("Assoziierte Binary {} wurde erfolgreich gelöscht.", currentBinaryId);
        }

        // Prüfen, ob assoziierte Invoices gelöscht wurden
        for (String invoiceId : associatedInvoiceIds) {
            final String currentInvoiceId = invoiceId; // Für Lambda-Ausdruck
            assertThrows(ResourceGoneException.class, () -> {
                super.client.read().resource(Invoice.class).withId(currentInvoiceId).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
            }, "Invoice " + currentInvoiceId + " sollte nicht mehr existieren.");
            LOGGER.info("Assoziierte Invoice {} wurde erfolgreich gelöscht.", currentInvoiceId);
        }
        
        // Prüfen, ob verlinkte Anhang-DocumentReferences gelöscht wurden
        for (String anhangDocId : relatedAttachmentDocRefIds) {
            final String currentAnhangDocId = anhangDocId; // Für Lambda-Ausdruck
             assertThrows(ResourceGoneException.class, () -> {
                super.client.read().resource(DocumentReference.class).withId(currentAnhangDocId).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
            }, "Anhang DocumentReference " + currentAnhangDocId + " sollte nicht mehr existieren.");
            LOGGER.info("Anhang DocumentReference {} wurde erfolgreich gelöscht.", currentAnhangDocId);
        }

        // Stelle sicher, dass die Patientenressource NICHT gelöscht wurde
        assertNotNull(super.testPatient, "testPatient sollte noch existieren");
        try {
            Patient patientAfterErase = super.client.read().resource(Patient.class).withId(super.testPatient.getIdPart()).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
            assertNotNull(patientAfterErase, "Patientenressource sollte nach $erase noch abrufbar sein.");
            LOGGER.info("Patientenressource {} existiert weiterhin.", patientAfterErase.getIdPart());
        } catch (Exception e) {
            fail("Fehler beim Überprüfen der Patientenressource nach $erase: " + e.getMessage());
        }

        LOGGER.info("Test Erfolgreiche $erase Operation BEENDET.");
    }

    @Test
    void testEraseWithWrongStatus() {
        LOGGER.info("Starte Test: $erase mit falschem Dokumentstatus.");
        // 1. Neues Dokument einreichen (Standardstatus ist 'offen', nicht 'papierkorb')
        DocumentReference docWithWrongStatus = null;
        String versichertenAuthHeader = "Bearer " + super.getValidAccessToken("EGK1");
        String leistungserbringerAuthHeader = "Bearer " + super.getValidAccessToken("SMCB_KRANKENHAUS");
        try {
            Parameters submitParams = new Parameters();
            submitParams.addParameter().setName("rechnung").setResource(super.testRechnungDocRef.copy());
            submitParams.addParameter().setName("modus").setValue(new CodeType("normal"));
            Parameters submitResult = super.client.operation()
                .onInstance(super.testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(submitParams)
                .withAdditionalHeader("Authorization", leistungserbringerAuthHeader)
                .execute();
            String docId = ((StringType) submitResult.getParameter("ergToken").getValue()).getValue();
            docWithWrongStatus = super.client.read().resource(DocumentReference.class).withId(docId).withAdditionalHeader("Authorization", versichertenAuthHeader).execute();
        } catch (Exception e) {
            fail("Fehler bei der Vorbereitung des Dokuments mit falschem Status: " + e.getMessage());
        }

        final DocumentReference finalDocWithWrongStatus = docWithWrongStatus;
        InvalidRequestException exception = assertThrows(InvalidRequestException.class, () -> {
            super.client.operation()
                .onInstance(finalDocWithWrongStatus.getIdElement())
                .named("$erase")
                .withNoParameters(Parameters.class)
                .withAdditionalHeader("Authorization", versichertenAuthHeader)
                .execute();
        });
        assertTrue(exception.getMessage().contains("befindet sich nicht im Status 'PAPIERKORB'"), "Fehlermeldung sollte Statusproblem anzeigen.");
        LOGGER.info("Test $erase mit falschem Dokumentstatus BEENDET.");
    }

    @Test
    void testEraseWithNonExistentId() {
        LOGGER.info("Starte Test: $erase mit nicht existierender ID.");
        String versichertenAuthHeader = "Bearer " + super.getValidAccessToken("EGK1");
        IdType nonExistentId = new IdType("DocumentReference", UUID.randomUUID().toString());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            super.client.operation()
                .onInstance(nonExistentId)
                .named("$erase")
                .withNoParameters(Parameters.class)
                .withAdditionalHeader("Authorization", versichertenAuthHeader)
                .execute();
        });
        // HAPI FHIR Standardmeldung für nicht gefundene Ressourcen
        assertTrue(exception.getMessage().contains("is not known") || exception.getMessage().contains("HAPI-2001"));
        LOGGER.info("Test $erase mit nicht existierender ID BEENDET.");
    }

    @Test
    void testEraseWithoutToken() {
        LOGGER.info("Starte Test: $erase ohne Authorization Token.");
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> {
            super.client.operation()
                .onInstance(testDocumentForErase.getIdElement()) // testDocumentForErase ist bereits im Papierkorb
                .named("$erase")
                .withNoParameters(Parameters.class)
                .execute();
        });
        assertTrue(exception.getMessage().toLowerCase().contains("authorization") || exception.getMessage().toLowerCase().contains("access token"));
        LOGGER.info("Test $erase ohne Authorization Token BEENDET.");
    }

    @Test
    void testEraseWithLeistungserbringerToken() {
        LOGGER.info("Starte Test: $erase mit Leistungserbringer-Token.");
        String leistungserbringerAuthHeader = "Bearer " + super.getValidAccessToken("SMCB_KRANKENHAUS");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class, () -> {
            super.client.operation()
                .onInstance(testDocumentForErase.getIdElement()) // testDocumentForErase ist bereits im Papierkorb
                .named("$erase")
                .withNoParameters(Parameters.class)
                .withAdditionalHeader("Authorization", leistungserbringerAuthHeader)
                .execute();
        });
        // Erwartete Meldung aus AuthorizationService, wenn die Profession nicht passt
        assertTrue(exception.getMessage().contains("Für diese Operation sind nur Versicherte und Kostenträger zugelassen.") || exception.getMessage().contains("Aktuelle Profession: ARZT_KRANKENHAUS"));
        LOGGER.info("Test $erase mit Leistungserbringer-Token BEENDET.");
    }


    // TODO: Test für fehlenden Scope 'invoiceDoc.d', falls die Scope-Prüfung spezifisch implementiert wird.
    // Hierfür müsste der AuthorizationService oder der Token-Generierungsprozess angepasst werden,
    // um Tokens ohne diesen spezifischen Scope zu erzeugen.
} 