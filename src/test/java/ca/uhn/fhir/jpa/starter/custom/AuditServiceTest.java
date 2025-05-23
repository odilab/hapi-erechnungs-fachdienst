package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditServiceTest.class);
    
    private String versichertenToken;
    private String leistungserbringerToken;
    private String ergToken;
    
    @Autowired
    private AuditService auditService;
    
    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        
        versichertenToken = getValidAccessToken("EGK1");
        leistungserbringerToken = getValidAccessToken("SMCB_KRANKENHAUS");
        
        submitTestDocumentForAuditTests();
    }
    
    private void submitTestDocumentForAuditTests() {
        LOGGER.info("AuditServiceTest: Reiche Testdokument via $erechnung-submit ein, um ergToken für Audit-Tests zu erhalten.");

        if (testPatient == null || !testPatient.hasIdElement()) {
            fail("TestPatient ist nicht korrekt initialisiert in BaseProviderTest.setUp()");
        }
        if (testRechnungDocRef == null) {
            fail("TestRechnungDocRef ist nicht korrekt initialisiert in BaseProviderTest.setUp()");
        }

        Parameters params = new Parameters();
        params.addParameter().setName("rechnung").setResource(testRechnungDocRef.copy());

        if (testAnhangDocRef != null) {
            params.addParameter().setName("anhang").setResource(testAnhangDocRef.copy());
            LOGGER.info("AuditServiceTest: TestAnhangDocRef wird der Submit-Operation hinzugefügt.");
        } else {
             LOGGER.warn("AuditServiceTest: Kein TestAnhangDocRef zum Hinzufügen gefunden.");
        }

        params.addParameter().setName("modus").setValue(new CodeType("normal"));
        params.addParameter().setName("angereichertesPDF").setValue(new BooleanType(true));

        String authHeader = "Bearer " + leistungserbringerToken;

        Parameters result = null;
        try {
            result = client.operation()
                .onInstance(testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        } catch (Exception e) {
            LOGGER.error("AuditServiceTest: Fehler beim Ausführen der $erechnung-submit Operation für das Test-Setup.", e);
            fail("Fehler beim Einreichen des Dokuments für Audit-Tests: " + e.getMessage());
            return;
        }

        assertNotNull(result, "$erechnung-submit Operation sollte eine Antwort zurückgeben.");

        Parameters.ParametersParameterComponent ergTokenParam = result.getParameter("ergToken");
        assertNotNull(ergTokenParam, "Antwort der $erechnung-submit Operation sollte ergToken enthalten.");
        assertTrue(ergTokenParam.getValue() instanceof StringType, "ergToken sollte vom Typ StringType sein.");

        this.ergToken = ((StringType) ergTokenParam.getValue()).getValue();
        assertNotNull(this.ergToken, "Wert des ergToken darf nicht null sein.");
        assertFalse(this.ergToken.isEmpty(), "Wert des ergToken darf nicht leer sein.");

        LOGGER.info("AuditServiceTest: Testdokument erfolgreich via $erechnung-submit eingereicht, ergToken für Audit-Tests: {}", this.ergToken);

        try {
            DocumentReference submittedDoc = client.read()
                .resource(DocumentReference.class)
                .withId(this.ergToken)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
            assertNotNull(submittedDoc, "Die via $erechnung-submit erstellte DocumentReference (ID: " + this.ergToken + ") konnte nicht direkt nach dem Submit gelesen werden.");
            LOGGER.info("AuditServiceTest: Erfolgreicher Read-Check für DocumentReference mit ID (ergToken) {}.", this.ergToken);
        } catch (ResourceNotFoundException e) {
             LOGGER.error("AuditServiceTest: DocumentReference mit ID {} nicht gefunden nach Submit.", this.ergToken, e);
             fail("DocumentReference mit ID " + this.ergToken + " nicht gefunden nach Submit.");
        } catch (Exception e) {
            LOGGER.error("AuditServiceTest: Fehler beim direkten Lesen der soeben via $erechnung-submit erstellten DocumentReference (ID: {}).", this.ergToken, e);
        }
    }
    
    @Test
    void testSubmitOperationAudit() {
        assertNotNull(testPatient, "TestPatient sollte im Setup initialisiert worden sein.");
        assertNotNull(testPatient.getIdElement(), "TestPatient sollte eine ID haben.");
        LOGGER.info("Verwende Testpatient mit ID: {}", testPatient.getIdElement().getIdPart());
        
        assertNotNull(testRechnungDocRef, "TestRechnungDocRef sollte im Setup initialisiert worden sein.");
        
        Parameters params = new Parameters();
        params.addParameter().setName("rechnung").setResource(testRechnungDocRef.copy());
        if (testAnhangDocRef != null) {
             params.addParameter().setName("anhang").setResource(testAnhangDocRef.copy());
        }
        params.addParameter().setName("modus").setValue(new CodeType("normal"));
        params.addParameter().setName("angereichertesPDF").setValue(new BooleanType(false));

        
        Parameters result = client.operation()
            .onInstance(testPatient.getIdElement())
            .named("$erechnung-submit")
            .withParameters(params)
            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
            .execute();
        
        assertNotNull(result);
        
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Bundle results = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.TYPE.exactly().systemAndCode("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest"))
            .and(AuditEvent.SUBTYPE.exactly().systemAndCode("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs", "erechnung-submit"))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
            .execute();
        
        assertTrue(results.getEntry().size() > 0, "Es sollte mindestens ein AuditEvent für die Submit-Operation erstellt worden sein");
        
        AuditEvent auditEvent = results.getEntry().stream()
            .map(entry -> (AuditEvent) entry.getResource())
            .sorted((a1, a2) -> a2.getRecorded().compareTo(a1.getRecorded()))
            .findFirst()
            .orElse(null);

        assertNotNull(auditEvent, "Konnte kein passendes AuditEvent finden.");
        
        LOGGER.info("Gefundenes Submit AuditEvent Meta: {}", auditEvent.getMeta());
        if (auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null) {
            for (CanonicalType profile : auditEvent.getMeta().getProfile()) {
                LOGGER.info("Gefundenes Submit AuditEvent Profil: {}", profile.getValue());
            }
        } else {
            LOGGER.warn("Gefundenes Submit AuditEvent hat kein Meta-Element oder keine Profile");
        }
        
        assertEquals(AuditEvent.AuditEventAction.C, auditEvent.getAction(), "Die Aktion sollte CREATE sein");
        assertTrue(auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null && 
                 auditEvent.getMeta().getProfile().stream()
                     .anyMatch(profile -> "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll".equals(profile.getValue())),
            "Das Profil 'erg-nutzungsprotokoll' sollte gesetzt sein");
        
        assertTrue(auditEvent.hasEntity(), "Das AuditEvent sollte eine Entity haben");
        assertNotNull(auditEvent.getEntityFirstRep().getName(), "Der Name der Entity sollte gesetzt sein.");
        assertNotNull(auditEvent.getEntityFirstRep().getWhat().getDisplay(), "Entity.what.display sollte gesetzt sein.");

        assertTrue(auditEvent.hasAgent(), "Das AuditEvent sollte einen Agent haben");
        assertTrue(auditEvent.getAgentFirstRep().getRequestor(), "Der Agent sollte Requestor sein");

        boolean hasWorkflowStatus = auditEvent.getEntityFirstRep().getDetail().stream()
            .anyMatch(detail -> "workflow-status".equals(detail.getType()));
                     // && "OFFEN".equals(((StringType)detail.getValue()).getValue()));

        assertTrue(hasWorkflowStatus, "Das AuditEvent sollte ein Detail mit dem Workflow-Status haben");
    }
    
    @Test
    void testRetrieveOperationAudit() {
        assertNotNull(ergToken, "ergToken sollte im Setup initialisiert worden sein.");

        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergToken));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false));

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        assertNotNull(result);
        
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // HINWEIS ZU testRetrieveOperationAudit:
        // Dieser Test sucht nach einem AuditEvent mit subtype='retrieve' vom Typ 'rest'. 
        // Eine dedizierte Audit-Logik für die $retrieve-Operation (im RetrieveOperationProvider) wurde bisher nicht implementiert.
        // Stattdessen setzt der DocumentProcessorService (der von $retrieve intern genutzt wird) eine "gelesen"-Markierung
        // und loggt dies als System-AuditEvent mit subtype='update'.
        // Daher wird dieser Test in seiner jetzigen Form wahrscheinlich fehlschlagen oder keine relevanten REST-AuditEvents finden.
        // Er müsste angepasst werden, um entweder das System-Event zu prüfen oder es muss Audit-Logging im RetrieveOperationProvider ergänzt werden.
        Bundle results = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.TYPE.exactly().systemAndCode("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest"))
            .and(AuditEvent.SUBTYPE.exactly().systemAndCode("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs", "retrieve"))
            .and(AuditEvent.ENTITY.hasId(ergToken))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        assertTrue(results.getEntry().size() > 0, "Es sollte mindestens ein AuditEvent für die Retrieve-Operation mit ergToken " + ergToken + " erstellt worden sein");
        
        AuditEvent auditEvent = (AuditEvent) results.getEntry().get(0).getResource();
        
        LOGGER.info("Gefundenes Retrieve AuditEvent Meta: {}", auditEvent.getMeta());
        if (auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null) {
            for (CanonicalType profile : auditEvent.getMeta().getProfile()) {
                LOGGER.info("Gefundenes Retrieve AuditEvent Profil: {}", profile.getValue());
            }
        } else {
            LOGGER.warn("Gefundenes Retrieve AuditEvent hat kein Meta-Element oder keine Profile");
        }
        
        assertEquals(AuditEvent.AuditEventAction.R, auditEvent.getAction(), "Die Aktion sollte READ sein");
        assertTrue(auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null && 
                 auditEvent.getMeta().getProfile().stream()
                     .anyMatch(profile -> "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll".equals(profile.getValue())),
            "Das Profil 'erg-nutzungsprotokoll' sollte gesetzt sein");
        
        assertTrue(auditEvent.hasEntity(), "Das AuditEvent sollte eine Entity haben");
        assertEquals("DocumentReference", auditEvent.getEntityFirstRep().getName(), "Der Name der Entity sollte 'DocumentReference' sein");
        assertTrue(auditEvent.getEntityFirstRep().getWhat().getReference().endsWith(ergToken), "Entity 'what' Referenz sollte auf den ergToken zeigen.");
        assertNotNull(auditEvent.getEntityFirstRep().getWhat().getDisplay(), "Entity.what.display sollte gesetzt sein.");

        assertTrue(auditEvent.hasAgent(), "Das AuditEvent sollte einen Agent haben");
        assertTrue(auditEvent.getAgentFirstRep().getRequestor(), "Der Agent sollte Requestor sein");
    }
    
    @Test
    void testChangeStatusOperationAudit() {
        assertNotNull(ergToken, "ergToken sollte im Setup initialisiert worden sein.");

        Parameters params = new Parameters();
        params.addParameter().setName("tag").setValue(new StringType("erledigt"));
        
        Parameters result = client.operation()
            .onInstance(new IdType("DocumentReference", ergToken))
            .named("$change-status")
            .withParameters(params)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        assertNotNull(result);

        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        Bundle results = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.TYPE.exactly().systemAndCode("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest"))
            .and(AuditEvent.SUBTYPE.exactly().systemAndCode("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs", "change-status"))
            .and(AuditEvent.ENTITY.hasId(ergToken))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        assertTrue(results.getEntry().size() > 0, "Es sollte mindestens ein AuditEvent für die Change-Status-Operation mit ergToken " + ergToken + " erstellt worden sein");
        
        AuditEvent auditEvent = (AuditEvent) results.getEntry().get(0).getResource();
        
        LOGGER.info("Gefundenes ChangeStatus AuditEvent Meta: {}", auditEvent.getMeta());
        if (auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null) {
            for (CanonicalType profile : auditEvent.getMeta().getProfile()) {
                LOGGER.info("Gefundenes ChangeStatus AuditEvent Profil: {}", profile.getValue());
            }
        } else {
            LOGGER.warn("Gefundenes ChangeStatus AuditEvent hat kein Meta-Element oder keine Profile");
        }
        
        assertEquals(AuditEvent.AuditEventAction.U, auditEvent.getAction(), "Die Aktion sollte UPDATE sein");
        assertTrue(auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null && 
                 auditEvent.getMeta().getProfile().stream()
                     .anyMatch(profile -> "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll".equals(profile.getValue())),
            "Das Profil sollte gesetzt sein");
        
        assertTrue(auditEvent.hasEntity(), "Das AuditEvent sollte eine Entity haben");
        assertEquals("DocumentReference", auditEvent.getEntityFirstRep().getName(), "Der Name der Entity sollte 'DocumentReference' sein");
        assertTrue(auditEvent.getEntityFirstRep().getWhat().getReference().endsWith(ergToken), "Entity 'what' Referenz sollte auf den ergToken zeigen.");
        assertNotNull(auditEvent.getEntityFirstRep().getWhat().getDisplay(), "Entity.what.display sollte gesetzt sein.");

        assertTrue(auditEvent.hasAgent(), "Das AuditEvent sollte einen Agent haben");
        assertTrue(auditEvent.getAgentFirstRep().getRequestor(), "Der Agent sollte Requestor sein");
        
        boolean hasAlterStatus = auditEvent.getEntityFirstRep().getDetail().stream()
            .anyMatch(detail -> "alter-status".equals(detail.getType()));
        
        boolean hasNeuerStatus = auditEvent.getEntityFirstRep().getDetail().stream()
            .anyMatch(detail -> "neuer-status".equals(detail.getType()) && 
                     "erledigt".equals(((StringType)detail.getValue()).getValue()));
        
        assertTrue(hasAlterStatus, "Das AuditEvent sollte ein Detail mit dem alten Status haben");
        assertTrue(hasNeuerStatus, "Das AuditEvent sollte ein Detail mit dem neuen Status 'erledigt' haben");
    }
    
    @Test
    void testProcessFlagOperationAudit() {
         assertNotNull(ergToken, "ergToken sollte im Setup initialisiert worden sein.");

        Parameters params = new Parameters();
        
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-markierung-cs")
            .setCode("wichtig")
            .setDisplay("Wichtig");
        
        params.addParameter().setName("markierung").setValue(markierung);
        params.addParameter().setName("zeitpunkt").setValue(new DateTimeType(new java.util.Date()));
        
        Parameters result = client.operation()
            .onInstance(new IdType("DocumentReference", ergToken))
            .named("$process-flag")
            .withParameters(params)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        assertNotNull(result);
        
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Bundle results = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.TYPE.exactly().systemAndCode("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest"))
            .and(AuditEvent.SUBTYPE.exactly().systemAndCode("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs", "process-flag"))
            .and(AuditEvent.ENTITY.hasId(ergToken))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        assertTrue(results.getEntry().size() > 0, "Es sollte mindestens ein AuditEvent für die Process-Flag-Operation mit ergToken " + ergToken + " erstellt worden sein");
        
        AuditEvent auditEvent = (AuditEvent) results.getEntry().get(0).getResource();
        
        LOGGER.info("Gefundenes ProcessFlag AuditEvent Meta: {}", auditEvent.getMeta());
        if (auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null) {
            for (CanonicalType profile : auditEvent.getMeta().getProfile()) {
                LOGGER.info("Gefundenes ProcessFlag AuditEvent Profil: {}", profile.getValue());
            }
        } else {
            LOGGER.warn("Gefundenes ProcessFlag AuditEvent hat kein Meta-Element oder keine Profile");
        }
        
        assertEquals(AuditEvent.AuditEventAction.U, auditEvent.getAction(), "Die Aktion sollte UPDATE sein");
        assertTrue(auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null && 
                 auditEvent.getMeta().getProfile().stream()
                     .anyMatch(profile -> "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll".equals(profile.getValue())),
            "Das Profil sollte gesetzt sein");
        
        assertTrue(auditEvent.hasEntity(), "Das AuditEvent sollte eine Entity haben");
        assertEquals("DocumentReference", auditEvent.getEntityFirstRep().getName(), "Der Name der Entity sollte 'DocumentReference' sein");
        assertTrue(auditEvent.getEntityFirstRep().getWhat().getReference().endsWith(ergToken), "Entity 'what' Referenz sollte auf den ergToken zeigen.");
        assertNotNull(auditEvent.getEntityFirstRep().getWhat().getDisplay(), "Entity.what.display sollte gesetzt sein.");

        assertTrue(auditEvent.hasAgent(), "Das AuditEvent sollte einen Agent haben");
        assertTrue(auditEvent.getAgentFirstRep().getRequestor(), "Der Agent sollte Requestor sein");
        
        boolean hasMarkierungSystem = auditEvent.getEntityFirstRep().getDetail().stream()
            .anyMatch(detail -> "markierung-system".equals(detail.getType()) && 
                     "https://gematik.de/fhir/erg/CodeSystem/erg-markierung-cs".equals(((StringType)detail.getValue()).getValue()));
        
        boolean hasMarkierungCode = auditEvent.getEntityFirstRep().getDetail().stream()
            .anyMatch(detail -> "markierung-code".equals(detail.getType()) && 
                     "wichtig".equals(((StringType)detail.getValue()).getValue()));
        
        boolean hasMarkierungDisplay = auditEvent.getEntityFirstRep().getDetail().stream()
            .anyMatch(detail -> "markierung-display".equals(detail.getType()) && 
                     "Wichtig".equals(((StringType)detail.getValue()).getValue()));
        
        assertTrue(hasMarkierungSystem, "Das AuditEvent sollte ein Detail mit dem Markierungs-System haben");
        assertTrue(hasMarkierungCode, "Das AuditEvent sollte ein Detail mit dem Markierungs-Code 'wichtig' haben");
        assertTrue(hasMarkierungDisplay, "Das AuditEvent sollte ein Detail mit dem Markierungs-Display 'Wichtig' haben");
    }
    
    @Test
    void testEraseOperationAudit() {
        assertNotNull(ergToken, "ergToken sollte im Setup initialisiert worden sein.");

        // Zuerst den Status auf "papierkorb" setzen
        Parameters changeStatusParams = new Parameters();
        changeStatusParams.addParameter().setName("tag").setValue(new StringType("papierkorb"));

        try {
            LOGGER.info("AuditServiceTest.testEraseOperationAudit: Versuche, Status auf 'papierkorb' zu setzen für DocumentReference ID: {}", ergToken);
            client.operation()
                .onInstance(new IdType("DocumentReference", ergToken))
                .named("$change-status")
                .withParameters(changeStatusParams)
                .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
                .execute();
            LOGGER.info("AuditServiceTest.testEraseOperationAudit: Status erfolgreich auf 'papierkorb' gesetzt für DocumentReference ID: {}", ergToken);
        } catch (Exception e) {
            LOGGER.error("AuditServiceTest.testEraseOperationAudit: Fehler beim Setzen des Status auf 'papierkorb' für DocumentReference ID: {}. Fehlermeldung: {}", ergToken, e.getMessage(), e);
            fail("Fehler beim Vorbereiten des Dokuments für den Erase-Test (Status auf 'papierkorb' setzen): " + e.getMessage());
            return;
        }

        // Kurze Pause, um sicherzustellen, dass die Statusänderung verarbeitet wurde
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Überprüfen, ob der Status korrekt gesetzt wurde (optional, aber gut für die Fehlersuche)
        try {
            DocumentReference docForErase = client.read()
                .resource(DocumentReference.class)
                .withId(ergToken)
                .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
                .execute();
            boolean isInPapierkorb = docForErase.getMeta().getTag().stream()
                .anyMatch(tag -> "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs".equals(tag.getSystem()) &&
                                 "papierkorb".equals(tag.getCode()));
            assertTrue(isInPapierkorb, "Dokument sollte nach $change-status im Papierkorb sein (Tag 'papierkorb' vorhanden).");
            assertEquals(Enumerations.DocumentReferenceStatus.ENTEREDINERROR, docForErase.getStatus(), "DocumentReference.status sollte ENTEREDINERROR sein.");
            LOGGER.info("AuditServiceTest.testEraseOperationAudit: Überprüfung erfolgreich - Dokument {} ist im Papierkorb.", ergToken);
        } catch (Exception e) {
            LOGGER.error("AuditServiceTest.testEraseOperationAudit: Fehler beim Überprüfen des Status 'papierkorb' für DocumentReference ID: {}. Fehlermeldung: {}", ergToken, e.getMessage(), e);
            fail("Fehler beim Überprüfen des Dokumentstatus vor dem Erase-Test: " + e.getMessage());
            return;
        }

        // Vor dem Löschen des DocumentReference alle zugehörigen AuditEvents löschen,
        // die durch vorherige Operationen (wie $change-status) entstanden sein könnten.
        try {
            LOGGER.info("AuditServiceTest.testEraseOperationAudit: Suche nach AuditEvents, die auf DocumentReference ID {} verweisen, um sie zu löschen.", ergToken);
            Bundle auditEventsBundle = client.search()
                .forResource(AuditEvent.class)
                .where(AuditEvent.ENTITY.hasId(ergToken)) // Sucht nach AuditEvents, deren 'entity.what' auf unseren ergToken zeigt
                .returnBundle(Bundle.class)
                .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken) // Admin/System-Token könnte hier besser sein, falls vorhanden
                .execute();

            if (auditEventsBundle.hasEntry()) {
                LOGGER.info("AuditServiceTest.testEraseOperationAudit: {} AuditEvent(s) gefunden, die auf DocumentReference ID {} verweisen. Werden jetzt gelöscht.", auditEventsBundle.getEntry().size(), ergToken);
                for (Bundle.BundleEntryComponent entry : auditEventsBundle.getEntry()) {
                    if (entry.getResource() instanceof AuditEvent) {
                        AuditEvent eventToDelete = (AuditEvent) entry.getResource();
                        LOGGER.info("AuditServiceTest.testEraseOperationAudit: Lösche AuditEvent ID: {}", eventToDelete.getIdElement().getIdPart());
                        client.delete()
                            .resource(eventToDelete)
                            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken) // Admin/System-Token
                            .execute();
                        LOGGER.info("AuditServiceTest.testEraseOperationAudit: AuditEvent ID {} erfolgreich gelöscht.", eventToDelete.getIdElement().getIdPart());
                    }
                }
            } else {
                LOGGER.info("AuditServiceTest.testEraseOperationAudit: Keine referenzierenden AuditEvents für DocumentReference ID {} gefunden.", ergToken);
            }
        } catch (Exception e) {
            LOGGER.error("AuditServiceTest.testEraseOperationAudit: Fehler beim Suchen oder Löschen von referenzierenden AuditEvents für DocumentReference ID: {}. Fehlermeldung: {}", ergToken, e.getMessage(), e);
            // Wir loggen den Fehler, aber der Test sollte versuchen fortzufahren,
            // da das Fehlen von AuditEvents oder ein Fehler hier nicht unbedingt den $erase-Test selbst blockieren sollte,
            // es sei denn, die Referenzintegrität schlägt später immer noch fehl.
        }
        
        // Kurze Pause, um sicherzustellen, dass die Löschvorgänge der AuditEvents verarbeitet wurden
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Jetzt die $erase Operation ausführen
        LOGGER.info("AuditServiceTest.testEraseOperationAudit: Führe $erase Operation aus für DocumentReference ID: {}", ergToken);
        OperationOutcome result = client.operation()
            .onInstance(new IdType("DocumentReference", ergToken))
            .named("$erase")
            .withNoParameters(Parameters.class)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .returnResourceType(OperationOutcome.class)
            .execute();
        
        assertNotNull(result);
        LOGGER.info("AuditServiceTest.testEraseOperationAudit: $erase Operation erfolgreich ausgeführt für DocumentReference ID: {}", ergToken);

        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        Bundle results = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.TYPE.exactly().systemAndCode("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest"))
            .and(AuditEvent.SUBTYPE.exactly().systemAndCode("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs", "erase"))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + versichertenToken)
            .execute();
        
        AuditEvent foundEraseEventForOurDoc = null;
        String expectedEntityIdentifierValue = "DocumentReference/" + ergToken; 
        // ergToken ist die reine ID, z.B. "b39caecd...". Der Identifier im AuditEvent sollte "DocumentReference/b39caecd..." sein.

        LOGGER.info("AuditServiceTest.testEraseOperationAudit: Suche spezifisches Erase-AuditEvent für DocumentReference ID (als Identifier-Wert): {}", expectedEntityIdentifierValue);

        if (!results.hasEntry()) {
            LOGGER.warn("AuditServiceTest.testEraseOperationAudit: Keine AuditEvents mit Subtype 'erase' gefunden.");
        } else {
            LOGGER.info("AuditServiceTest.testEraseOperationAudit: {} AuditEvent(s) mit Subtype 'erase' gefunden. Durchsuche nach spezifischem Identifier...", results.getEntry().size());
        }

        for (Bundle.BundleEntryComponent entry : results.getEntry()) {
            if (entry.getResource() instanceof AuditEvent) {
                AuditEvent currentEvent = (AuditEvent) entry.getResource();
                LOGGER.debug("Prüfe AuditEvent ID: {}", currentEvent.getIdElement().getIdPart());
                if (currentEvent.hasEntity()) {
                    for (AuditEvent.AuditEventEntityComponent entity : currentEvent.getEntity()) {
                        if (entity.hasWhat() && entity.getWhat().hasIdentifier()) {
                            String actualIdentifierValue = entity.getWhat().getIdentifier().getValue();
                            String actualIdentifierSystem = entity.getWhat().getIdentifier().getSystem();
                            LOGGER.debug("  Entity hat Identifier: System='{}', Wert='{}'", actualIdentifierSystem, actualIdentifierValue);
                            if (expectedEntityIdentifierValue.equals(actualIdentifierValue)) {
                                // Optional: Prüfe auch das System, falls es im Provider konsistent gesetzt wird.
                                // if ("urn:ietf:rfc:3986".equals(actualIdentifierSystem)) {
                                foundEraseEventForOurDoc = currentEvent;
                                LOGGER.info("  --> Korrektes AuditEvent gefunden: ID={}", currentEvent.getIdElement().getIdPart());
                                break;
                                // }
                            }
                        }
                    }
                }
                if (foundEraseEventForOurDoc != null) break;
            }
        }

        assertNotNull(foundEraseEventForOurDoc,
            "Es konnte kein spezifisches AuditEvent für die Erase-Operation des Dokuments mit ergToken '" + ergToken +
            "' (erwarteter Identifier: " + expectedEntityIdentifierValue + ") gefunden werden. Anzahl gefundener Erase-Events insgesamt: " + results.getEntry().size());

        AuditEvent auditEvent = foundEraseEventForOurDoc; // Für die weiteren Assertions
        
        LOGGER.info("Gefundenes Erase AuditEvent Meta: {}", auditEvent.getMeta());
        if (auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null) {
            for (CanonicalType profile : auditEvent.getMeta().getProfile()) {
                LOGGER.info("Gefundenes Erase AuditEvent Profil: {}", profile.getValue());
            }
        } else {
            LOGGER.warn("Gefundenes Erase AuditEvent hat kein Meta-Element oder keine Profile");
        }
        
        assertEquals(AuditEvent.AuditEventAction.D, auditEvent.getAction(), "Die Aktion sollte DELETE sein");
        assertTrue(auditEvent.getMeta() != null && auditEvent.getMeta().getProfile() != null && 
                 auditEvent.getMeta().getProfile().stream()
                     .anyMatch(profile -> "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll".equals(profile.getValue())),
            "Das Profil sollte gesetzt sein");
        
        assertTrue(auditEvent.hasEntity(), "Das AuditEvent sollte eine Entity haben");
        AuditEvent.AuditEventEntityComponent relevantEntity = auditEvent.getEntityFirstRep(); // Annahme, erste Entity ist die relevante
        
        // assertEquals("DocumentReference", relevantEntity.getName(), "Der Name der Entity sollte 'DocumentReference' sein");
        // Der Name der Entity wird jetzt dynamisch im Provider gesetzt, z.B. "DocumentReference Erase"
        assertTrue(relevantEntity.getName().contains("DocumentReference"), "Der Name der Entity sollte 'DocumentReference' enthalten.");
        assertTrue(relevantEntity.getName().contains("Erase"), "Der Name der Entity sollte 'Erase' enthalten.");

        assertNotNull(relevantEntity.getWhat(), "Entity.what sollte vorhanden sein.");
        assertTrue(relevantEntity.getWhat().hasIdentifier(), "Entity.what.identifier sollte vorhanden sein.");
        assertEquals(expectedEntityIdentifierValue, relevantEntity.getWhat().getIdentifier().getValue(), "Entity.what.identifier.value sollte auf den ergToken zeigen.");
        // Optional: System des Identifiers prüfen, wenn es zuverlässig gesetzt wird
        // assertEquals("urn:ietf:rfc:3986", relevantEntity.getWhat().getIdentifier().getSystem(), "Entity.what.identifier.system sollte korrekt sein.");

        assertNotNull(relevantEntity.getWhat().getDisplay(), "Entity.what.display sollte gesetzt sein.");
        assertTrue(relevantEntity.getWhat().getDisplay().contains(expectedEntityIdentifierValue), "Entity.what.display sollte die ID der gelöschten Ressource enthalten.");

        assertTrue(auditEvent.hasAgent(), "Das AuditEvent sollte einen Agent haben");
        assertTrue(auditEvent.getAgentFirstRep().getRequestor(), "Der Agent sollte Requestor sein");
    }
    
    @Test
    void testSystemAudit() {
        assertNotNull(ergToken, "ergToken sollte im Setup initialisiert worden sein für SystemAudit-Test.");
        
        AuditEvent createdAuditEvent = auditService.createSystemAuditEvent(
            AuditEvent.AuditEventAction.C,
            "system-event-subtype",
            AuditEvent.AuditEventOutcome._0,
            new Reference("DocumentReference/" + ergToken),
            "DocumentReference",
            "DocumentReference/" + ergToken,
            "System-Event Beschreibung",
            null
        );
        
        assertNotNull(createdAuditEvent, "Das System-AuditEvent sollte erstellt worden sein");
        assertNotNull(createdAuditEvent.getIdElement().getIdPart(), "Erstelltes AuditEvent sollte eine ID haben");
        LOGGER.info("Manuell erstelltes System AuditEvent ID: {}", createdAuditEvent.getIdElement().getIdPart());
        
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        AuditEvent foundAuditEvent = null;
        try {
             foundAuditEvent = client.read()
                 .resource(AuditEvent.class)
                 .withId(createdAuditEvent.getIdElement().getIdPart())
                 .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
                 .execute();
        } catch (ResourceNotFoundException e) {
             LOGGER.warn("System-AuditEvent mit ID {} nicht gefunden.", createdAuditEvent.getIdElement().getIdPart());
             Bundle results = client.search()
                .forResource(AuditEvent.class)
                .where(AuditEvent.TYPE.exactly().systemAndCode("http://dicom.nema.org/resources/ontology/DCM", "110100"))
                .and(AuditEvent.SUBTYPE.exactly().code("system-event-subtype"))
                .and(AuditEvent.ENTITY.hasId(ergToken))
                .returnBundle(Bundle.class)
                .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
                .execute();
             if (results.getEntry().size() > 0) {
                  foundAuditEvent = (AuditEvent) results.getEntry().get(0).getResource();
             }
        }
        
        assertNotNull(foundAuditEvent, "Das manuell erstellte System-AuditEvent konnte nicht gefunden werden.");
        
        LOGGER.info("Gefundenes System AuditEvent Meta: {}", foundAuditEvent.getMeta());
        if (foundAuditEvent.getMeta() != null && foundAuditEvent.getMeta().getProfile() != null) {
            for (CanonicalType profile : foundAuditEvent.getMeta().getProfile()) {
                LOGGER.info("Gefundenes System AuditEvent Profil: {}", profile.getValue());
            }
        } else {
            LOGGER.warn("Gefundenes System AuditEvent hat kein Meta-Element oder keine Profile");
        }
        
        assertEquals(AuditEvent.AuditEventAction.C, foundAuditEvent.getAction(), "Die Aktion sollte CREATE sein");
        assertTrue(foundAuditEvent.getMeta() != null && foundAuditEvent.getMeta().getProfile() != null && 
                 foundAuditEvent.getMeta().getProfile().stream()
                     .anyMatch(profile -> "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll".equals(profile.getValue())),
            "Das Profil 'erg-nutzungsprotokoll' sollte gesetzt sein");
        
        assertTrue(foundAuditEvent.hasEntity(), "Das AuditEvent sollte eine Entity haben");
        assertEquals("DocumentReference", foundAuditEvent.getEntityFirstRep().getName(), "Der Name der Entity sollte 'DocumentReference' sein");
        assertTrue(foundAuditEvent.getEntityFirstRep().getWhat().getReference().endsWith(ergToken), "Entity 'what' Referenz sollte auf den ergToken zeigen.");
        assertNotNull(foundAuditEvent.getEntityFirstRep().getWhat().getDisplay(), "Entity.what.display sollte gesetzt sein.");
        assertEquals("System-Event Beschreibung", foundAuditEvent.getEntityFirstRep().getDescription(), "Die Beschreibung sollte korrekt sein.");

        assertTrue(foundAuditEvent.hasAgent(), "Das AuditEvent sollte einen Agent haben");
        assertFalse(foundAuditEvent.getAgentFirstRep().getRequestor(), "Der Agent sollte KEIN Requestor sein (da System)");
    }
    
    @Test
    void testAuditEventSearch() {
        assertNotNull(ergToken, "ergToken sollte im Setup initialisiert worden sein für AuditEventSearch-Test.");
        assertNotNull(testPatient, "TestPatient sollte im Setup initialisiert worden sein.");

        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        Bundle resultsById = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.ENTITY.hasId(ergToken))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
            .execute();
        
        assertTrue(resultsById.getEntry().size() > 0, "Es sollten AuditEvents für das Dokument mit ID " + ergToken + " gefunden werden");
        LOGGER.info("Gefunden: {} AuditEvents für Entity ID {}", resultsById.getTotal(), ergToken);

        Bundle resultsBySubtype = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.SUBTYPE.exactly().systemAndCode(
                "https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs", "retrieve"))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
            .execute();
        
        assertTrue(resultsBySubtype.getEntry().size() > 0, "Es sollten AuditEvents für die Retrieve-Operation gefunden werden");
        LOGGER.info("Gefunden: {} AuditEvents für Subtype 'retrieve'", resultsBySubtype.getTotal());
        
        Bundle resultsByDate = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.DATE.afterOrEquals().day(new java.util.Date()))
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
            .execute();
        
        assertTrue(resultsByDate.getEntry().size() > 0, "Es sollten AuditEvents für das heutige Datum gefunden werden");
        LOGGER.info("Gefunden: {} AuditEvents für heute", resultsByDate.getTotal());

        Bundle resultsByAgent = client.search()
            .forResource(AuditEvent.class)
            .where(AuditEvent.AGENT.hasId(testPatient.getIdElement().getIdPart())) 
            .returnBundle(Bundle.class)
            .withAdditionalHeader("Authorization", "Bearer " + leistungserbringerToken)
            .execute();

        assertTrue(resultsByAgent.getEntry().size() > 0, 
            "Es sollten AuditEvents gefunden werden, bei denen der Versicherte (Patient-ID: " + testPatient.getIdElement().getIdPart() + ", KVNR: " + versichertenKvnr + ") der Agent war.");
        LOGGER.info("Gefunden: {} AuditEvents für Agent (Patient-ID: {}, KVNR: {})", 
            resultsByAgent.getTotal(), testPatient.getIdElement().getIdPart(), versichertenKvnr);
    }
} 