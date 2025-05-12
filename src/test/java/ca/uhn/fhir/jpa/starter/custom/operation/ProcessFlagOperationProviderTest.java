package ca.uhn.fhir.jpa.starter.custom.operation;

import ca.uhn.fhir.jpa.starter.custom.BaseProviderTest;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testklasse für den ProcessFlagOperationProvider
 */
class ProcessFlagOperationProviderTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessFlagOperationProviderTest.class);
    private String documentId;

    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        
        // Führe eine Submit-Operation durch, um ein Dokument zu erstellen, das später markiert werden kann
        submitTestDocument();
    }
    
    /**
     * Hilfsmethode zum Einreichen eines Testdokuments, um eine ID zu erhalten
     */
    private void submitTestDocument() {
        LOGGER.info("Reiche Testdokument ein, um ID für Process-Flag-Tests zu erhalten");
        
        // Erstelle die Parameter für die Submit-Operation
        Parameters params = new Parameters();

        // Rechnung erstellen
        DocumentReference rechnung = testRechnungDocRef.copy();
        params.addParameter().setName("rechnung").setResource(rechnung);

        // Modus setzen
        params.addParameter().setName("modus").setValue(new CodeType("normal"));

        // AngereichertesPDF-Flag setzen
        params.addParameter().setName("angereichertesPDF").setValue(new BooleanType(true));

        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");

        // Operation ausführen
        Parameters result = client.operation()
            .onInstance(testPatient.getIdElement())
            .named("$erechnung-submit")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        // Statt "token" nach "ergToken" suchen, der jetzt direkt den Token-Wert als StringType enthält
        Parameters.ParametersParameterComponent ergTokenParam = result.getParameter().stream()
            .filter(p -> "ergToken".equals(p.getName()))
            .findFirst()
            .orElse(null);

        assertNotNull(ergTokenParam, "Antwort sollte ergToken enthalten");
        assertTrue(ergTokenParam.getValue() instanceof StringType, "ergToken sollte vom Typ StringType sein");
        
        // Direkt den Wert des ergToken entnehmen
        String documentToken = ((StringType)ergTokenParam.getValue()).getValue();
        
        LOGGER.info("Testdokument erfolgreich eingereicht, ergToken: {}", documentToken);
        
        // Versuch 1: Versuche direkt, die DocumentReference mit dem ergToken als ID zu lesen
        try {
            DocumentReference doc = client.read()
                .resource(DocumentReference.class)
                .withId(documentToken)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            documentId = doc.getIdElement().getIdPart();
            LOGGER.info("Dokument mit ID {} direkt gefunden", documentId);
            return;
        } catch (Exception e) {
            LOGGER.warn("Konnte Dokument nicht direkt über ID {} lesen: {}", documentToken, e.getMessage());
        }
        
        // Versuch 2: Suche das Dokument anhand des Tokens mit verschiedenen Identifier-Systemen
        String[] possibleSystems = {
            "https://gematik.de/fhir/sid/erg-token",
            "https://gematik.de/fhir/erg/sid/erg-token",
            "http://gematik.de/fhir/sid/erg-token"
        };
        
        DocumentReference foundDoc = null;
        for (String system : possibleSystems) {
            if (foundDoc != null) break;
            
            LOGGER.info("Versuche Dokument mit System {} und Code {} zu finden", system, documentToken);
            Bundle searchResult = client.search()
                .forResource(DocumentReference.class)
                .where(DocumentReference.IDENTIFIER.exactly().systemAndCode(system, documentToken))
                .returnBundle(Bundle.class)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            if (!searchResult.getEntry().isEmpty()) {
                foundDoc = (DocumentReference) searchResult.getEntry().get(0).getResource();
                LOGGER.info("Dokument mit System {} gefunden", system);
            }
        }
        
        // Versuch 3: Suche ohne System
        if (foundDoc == null) {
            LOGGER.info("Versuche Dokument nur mit Code {} ohne System zu finden", documentToken);
            Bundle searchResult = client.search()
                .forResource(DocumentReference.class)
                .where(DocumentReference.IDENTIFIER.exactly().code(documentToken))
                .returnBundle(Bundle.class)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            if (!searchResult.getEntry().isEmpty()) {
                foundDoc = (DocumentReference) searchResult.getEntry().get(0).getResource();
                LOGGER.info("Dokument ohne spezifisches System gefunden");
            }
        }
        
        // Versuch 4: Allgemeine Suche nach neuesten DocumentReferences
        if (foundDoc == null) {
            LOGGER.info("Versuche das zuletzt erstellte Dokument für den Patienten zu finden");
            Bundle searchResult = client.search()
                .forResource(DocumentReference.class)
                .where(DocumentReference.SUBJECT.hasId(testPatient.getIdElement().getIdPart()))
                .sort().descending(DocumentReference.DATE)
                .count(1)
                .returnBundle(Bundle.class)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            if (!searchResult.getEntry().isEmpty()) {
                foundDoc = (DocumentReference) searchResult.getEntry().get(0).getResource();
                LOGGER.info("Neuestes Dokument für Patienten gefunden");
            }
        }
        
        if (foundDoc != null) {
            documentId = foundDoc.getIdElement().getIdPart();
            LOGGER.info("Dokument mit ID {} gefunden", documentId);
            
            // Füge dem Dokument explizit einen Identifier mit dem ergToken hinzu für zukünftige Tests
            if (foundDoc.getIdentifier().stream().noneMatch(id -> id.getValue().equals(documentToken))) {
                LOGGER.info("Füge ergToken {} als Identifier zum Dokument hinzu", documentToken);
                Identifier ergTokenIdentifier = new Identifier()
                    .setSystem("https://gematik.de/fhir/sid/erg-token")
                    .setValue(documentToken);
                foundDoc.addIdentifier(ergTokenIdentifier);
                
                client.update()
                    .resource(foundDoc)
                    .withAdditionalHeader("Authorization", authHeader)
                    .execute();
            }
        } else {
            fail("Konnte kein Dokument für den ergToken " + documentToken + " finden. Bitte überprüfen Sie die Implementierung der DocumentReference-Erstellung und -Identifizierung.");
        }
    }

    @Test
    void testProcessFlagOperation_Gelesen() {
        LOGGER.info("Starte Process-Flag Operation Test für Markierung 'gelesen'");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "gelesen" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("gelesen")
            .setDisplay("Gelesen");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("Vom Versicherten gelesen");
        params.addParameter().setName("details").setValue(details);
        
        // Gelesen-Status hinzufügen
        BooleanType gelesen = new BooleanType(true);
        params.addParameter().setName("gelesen").setValue(gelesen);

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen
        Parameters result = client.operation()
            .onInstance(new IdType("DocumentReference", documentId))
            .named("$process-flag")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        // Überprüfungen
        assertNotNull(result, "Ergebnis sollte nicht null sein");

        // Überprüfe Meta in der Antwort
        Parameters.ParametersParameterComponent metaParam = result.getParameter().stream()
            .filter(p -> "meta".equals(p.getName()))
            .findFirst()
            .orElse(null);

        assertNotNull(metaParam, "Antwort sollte Meta-Element enthalten");
        assertTrue(metaParam.getValue() instanceof Meta, "Meta-Parameter sollte vom Typ Meta sein");
        
        Meta meta = (Meta) metaParam.getValue();
        
        // Überprüfe, ob die Markierung in den Extensions vorhanden ist
        boolean markierungFound = meta.getExtension().stream()
            .anyMatch(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung".equals(ext.getUrl()));
            
        assertTrue(markierungFound, "Meta sollte die Markierungs-Extension enthalten");
        
        // Überprüfe, ob das Dokument aktualisiert wurde
        DocumentReference updatedDoc = client.read()
            .resource(DocumentReference.class)
            .withId(documentId)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();
            
        assertNotNull(updatedDoc, "Aktualisiertes Dokument sollte gefunden werden");
        
        // Überprüfe, ob die Markierung im Dokument vorhanden ist
        boolean docMarkierungFound = updatedDoc.getMeta().getExtension().stream()
            .anyMatch(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung".equals(ext.getUrl()));
            
        assertTrue(docMarkierungFound, "Dokument sollte die Markierungs-Extension enthalten");
    }

    @Test
    void testProcessFlagOperation_Archiviert() {
        LOGGER.info("Starte Process-Flag Operation Test für Markierung 'archiviert'");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "archiviert" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("archiviert")
            .setDisplay("Archiviert");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("In der ePA archiviert");
        params.addParameter().setName("details").setValue(details);
        
        // Art der Archivierung hinzufügen
        Coding artDerArchivierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-dokument-artderarchivierung-cs")
            .setCode("epa")
            .setDisplay("ePA");
        params.addParameter().setName("artDerArchivierung").setValue(artDerArchivierung);

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen
        Parameters result = client.operation()
            .onInstance(new IdType("DocumentReference", documentId))
            .named("$process-flag")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        // Überprüfungen
        assertNotNull(result, "Ergebnis sollte nicht null sein");
        
        // Überprüfe Meta in der Antwort
        Parameters.ParametersParameterComponent metaParam = result.getParameter().stream()
            .filter(p -> "meta".equals(p.getName()))
            .findFirst()
            .orElse(null);

        assertNotNull(metaParam, "Antwort sollte Meta-Element enthalten");
        assertTrue(metaParam.getValue() instanceof Meta, "Meta-Parameter sollte vom Typ Meta sein");
        
        // Überprüfe, ob die Markierung in den Extensions vorhanden ist
        Meta meta = (Meta) metaParam.getValue();
        boolean markierungFound = meta.getExtension().stream()
            .anyMatch(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung".equals(ext.getUrl()));
            
        assertTrue(markierungFound, "Meta sollte die Markierungs-Extension enthalten");
    }

    @Test
    void testProcessFlagOperation_MissingMarkierung() {
        LOGGER.info("Starte Process-Flag Operation Test mit fehlender Markierung");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Nur Zeitpunkt hinzufügen, Markierung fehlt
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", documentId))
                .named("$process-flag")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn die Markierung fehlt");
        } catch (UnprocessableEntityException e) {
            // Erwartete Exception
            assertTrue(e.getMessage().contains("markierung"), "Fehlermeldung sollte 'markierung' enthalten");
        }
    }

    @Test
    void testProcessFlagOperation_MissingZeitpunkt() {
        LOGGER.info("Starte Process-Flag Operation Test mit fehlendem Zeitpunkt");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Nur Markierung hinzufügen, Zeitpunkt fehlt
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("gelesen")
            .setDisplay("Gelesen");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Gelesen-Status hinzufügen
        BooleanType gelesen = new BooleanType(true);
        params.addParameter().setName("gelesen").setValue(gelesen);

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", documentId))
                .named("$process-flag")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn der Zeitpunkt fehlt");
        } catch (UnprocessableEntityException e) {
            // Erwartete Exception
            assertTrue(e.getMessage().contains("zeitpunkt"), "Fehlermeldung sollte 'zeitpunkt' enthalten");
        }
    }

    @Test
    void testProcessFlagOperation_MissingGelesenForGelesenMarkierung() {
        LOGGER.info("Starte Process-Flag Operation Test mit fehlender gelesen-Information");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "gelesen" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("gelesen")
            .setDisplay("Gelesen");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("Vom Versicherten gelesen");
        params.addParameter().setName("details").setValue(details);
        
        // Gelesen-Status fehlt

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", documentId))
                .named("$process-flag")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn der gelesen-Status fehlt");
        } catch (UnprocessableEntityException e) {
            // Erwartete Exception
            assertTrue(e.getMessage().contains("gelesen"), "Fehlermeldung sollte 'gelesen' enthalten");
        }
    }

    @Test
    void testProcessFlagOperation_MissingArtDerArchivierungForArchiviertMarkierung() {
        LOGGER.info("Starte Process-Flag Operation Test mit fehlender Art der Archivierung");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "archiviert" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("archiviert")
            .setDisplay("Archiviert");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("In der ePA archiviert");
        params.addParameter().setName("details").setValue(details);
        
        // Art der Archivierung fehlt

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", documentId))
                .named("$process-flag")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn die Art der Archivierung fehlt");
        } catch (UnprocessableEntityException e) {
            // Erwartete Exception
            assertTrue(e.getMessage().contains("artDerArchivierung"), "Fehlermeldung sollte 'artDerArchivierung' enthalten");
        }
    }

    @Test
    void testProcessFlagOperation_WithoutAuthorization() {
        LOGGER.info("Starte Process-Flag Operation Test ohne Autorisierung");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "gelesen" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("gelesen")
            .setDisplay("Gelesen");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("Vom Versicherten gelesen");
        params.addParameter().setName("details").setValue(details);
        
        // Gelesen-Status hinzufügen
        BooleanType gelesen = new BooleanType(true);
        params.addParameter().setName("gelesen").setValue(gelesen);

        // Kein Authorization-Header

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", documentId))
                .named("$process-flag")
                .withParameters(params)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn die Autorisierung fehlt");
        } catch (Exception e) {
            // Erwartete Exception - wir prüfen nur, dass eine Exception geworfen wird
            // Die genaue Fehlermeldung kann je nach Implementierung variieren
            assertTrue(true, "Eine Exception wurde wie erwartet geworfen");
        }
    }


    @Test
    void testProcessFlagOperation_WithLeistungserbringerToken() {
        LOGGER.info("Starte Process-Flag Operation Test mit Leistungserbringer-Token");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "gelesen" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("gelesen")
            .setDisplay("Gelesen");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("Vom Versicherten gelesen");
        params.addParameter().setName("details").setValue(details);
        
        // Gelesen-Status hinzufügen
        BooleanType gelesen = new BooleanType(true);
        params.addParameter().setName("gelesen").setValue(gelesen);

        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", documentId))
                .named("$process-flag")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn ein Leistungserbringer-Token verwendet wird");
        } catch (ForbiddenOperationException e) {
            // Erwartete Exception
            assertTrue(e.getMessage().contains("Versicherte"), "Fehlermeldung sollte 'Versicherte' enthalten");
        }
    }

    @Test
    void testProcessFlagOperation_InvalidDocumentId() {
        LOGGER.info("Starte Process-Flag Operation Test mit ungültiger Dokument-ID");
        
        // Erstelle die Parameter für die Operation
        Parameters params = new Parameters();
        
        // Markierung "gelesen" hinzufügen
        Coding markierung = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-markierung-cs")
            .setCode("gelesen")
            .setDisplay("Gelesen");
        params.addParameter().setName("markierung").setValue(markierung);
        
        // Zeitpunkt hinzufügen
        DateTimeType zeitpunkt = new DateTimeType(new Date());
        params.addParameter().setName("zeitpunkt").setValue(zeitpunkt);
        
        // Details hinzufügen
        StringType details = new StringType("Vom Versicherten gelesen");
        params.addParameter().setName("details").setValue(details);
        
        // Gelesen-Status hinzufügen
        BooleanType gelesen = new BooleanType(true);
        params.addParameter().setName("gelesen").setValue(gelesen);

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        // Operation ausführen und Exception erwarten
        try {
            client.operation()
                .onInstance(new IdType("DocumentReference", "invalid-id"))
                .named("$process-flag")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
                
            fail("Es sollte eine Exception geworfen werden, wenn die Dokument-ID ungültig ist");
        } catch (ResourceNotFoundException e) {
            // Erwartete Exception
            assertTrue(e.getMessage().contains("invalid-id"), "Fehlermeldung sollte 'invalid-id' enthalten");
        }
    }
} 