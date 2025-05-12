package ca.uhn.fhir.jpa.starter.custom.operation;

import ca.uhn.fhir.jpa.starter.custom.BaseProviderTest;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.jpa.starter.custom.operation.vau.VAUClientCrypto;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpHeaders;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;

class RetrieveOperationProviderTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetrieveOperationProviderTest.class);
    private VAUClientCrypto vauClientCrypto;
    private String ergTokenForRetrieveTest;

    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        
        try {
            vauClientCrypto = new VAUClientCrypto();
        } catch (Exception e) {
            LOGGER.error("Fehler bei der Initialisierung von VAUClientCrypto in RetrieveOperationProviderTest", e);
        }

        assertNotNull(testPatient, "testPatient muss von BaseProviderTest initialisiert worden sein.");
        assertNotNull(testRechnungDocRef, "testRechnungDocRef muss von BaseProviderTest initialisiert worden sein.");

        LOGGER.info("RetrieveOperationProviderTest.setUp(): Basisressourcen von BaseProviderTest sind initialisiert.");

        if (client != null && testPatient != null && testPatient.hasIdElement() && testRechnungDocRef != null) {
            submitDocumentForRetrieveTesting();
        } else {
            LOGGER.error("RetrieveOperationProviderTest.setUp(): Kritische Testressourcen (client, testPatient, testRechnungDocRef) sind null. Überspringe das Einreichen des Testdokuments.");
            fail("Kritische Testressourcen für das Setup der Retrieve-Tests sind nicht initialisiert.");
        }
    }
    
    private void submitDocumentForRetrieveTesting() {
        LOGGER.info("RetrieveOperationProviderTest: Reiche Testdokument via $erechnung-submit ein, um ergToken für Retrieve-Tests zu erhalten.");

        Parameters params = new Parameters();
        params.addParameter().setName("rechnung").setResource(testRechnungDocRef.copy());

        if (testAnhangDocRef != null) {
            params.addParameter().setName("anhang").setResource(testAnhangDocRef.copy());
            LOGGER.info("RetrieveOperationProviderTest: TestAnhangDocRef wird der Submit-Operation hinzugefügt.");
        }

        params.addParameter().setName("modus").setValue(new CodeType("normal"));
        params.addParameter().setName("angereichertesPDF").setValue(new BooleanType(true));

        String authHeader = "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS");

        Parameters result = null;
        try {
            result = client.operation()
                .onInstance(testPatient.getIdElement())
                .named("$erechnung-submit")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
        } catch (Exception e) {
            LOGGER.error("RetrieveOperationProviderTest: Fehler beim Ausführen der $erechnung-submit Operation für das Test-Setup.", e);
            fail("Fehler beim Einreichen des Dokuments für Retrieve-Tests: " + e.getMessage());
            return;
        }

        assertNotNull(result, "$erechnung-submit Operation sollte eine Antwort zurückgeben.");

        Parameters.ParametersParameterComponent ergTokenParam = result.getParameter("ergToken");
        assertNotNull(ergTokenParam, "Antwort der $erechnung-submit Operation sollte ergToken enthalten.");
        assertTrue(ergTokenParam.getValue() instanceof StringType, "ergToken sollte vom Typ StringType sein.");

        ergTokenForRetrieveTest = ((StringType) ergTokenParam.getValue()).getValue();
        assertNotNull(ergTokenForRetrieveTest, "Wert des ergToken darf nicht null sein.");
        assertFalse(ergTokenForRetrieveTest.isEmpty(), "Wert des ergToken darf nicht leer sein.");

        LOGGER.info("RetrieveOperationProviderTest: Testdokument erfolgreich via $erechnung-submit eingereicht, ergToken für Retrieve-Tests: {}", ergTokenForRetrieveTest);

        try {
            DocumentReference submittedDoc = client.read()
                .resource(DocumentReference.class)
                .withId(ergTokenForRetrieveTest)
                .withAdditionalHeader("Authorization", authHeader)
                .execute();
            assertNotNull(submittedDoc, "Die via $erechnung-submit erstellte DocumentReference (ID: " + ergTokenForRetrieveTest + ") konnte nicht direkt nach dem Submit gelesen werden.");
            LOGGER.info("RetrieveOperationProviderTest: Erfolgreicher Read-Check für DocumentReference mit ID (ergToken) {}.", ergTokenForRetrieveTest);
        } catch (Exception e) {
            LOGGER.error("RetrieveOperationProviderTest: Fehler beim direkten Lesen der soeben via $erechnung-submit erstellten DocumentReference (ID: {}).", ergTokenForRetrieveTest, e);
        }
    }

    @Test
    void testRetrieveOperation() {
        LOGGER.info("Starte Retrieve Operation Test - Fokus auf Metadaten und strukturierte Daten");
        
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(true));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false)); // Explizit nicht anfordern für diesen Test
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false)); // Explizit nicht anfordern

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .withAdditionalHeader("scope", "invoiceDoc.r")
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");

        // Prüfe Metadaten
        Parameters.ParametersParameterComponent metadataParam = result.getParameter("dokumentMetadaten");
        assertNotNull(metadataParam, "Antwort sollte 'dokumentMetadaten' enthalten");
        assertTrue(metadataParam.getResource() instanceof DocumentReference, "'dokumentMetadaten' sollte vom Typ DocumentReference sein");
        DocumentReference retrievedMetadata = (DocumentReference) metadataParam.getResource();
        assertNotNull(retrievedMetadata.getContent(), "'dokumentMetadaten' sollte eine Content-Liste haben.");
        assertFalse(retrievedMetadata.getContent().isEmpty(), "'dokumentMetadaten' Content-Liste sollte nicht leer sein und die ursprünglichen Elemente enthalten.");

        // Prüfe strukturierte Daten (Invoice)
        Parameters.ParametersParameterComponent strukturierteDatenParam = result.getParameter("strukturierteDaten");
        assertNotNull(strukturierteDatenParam, "Antwort sollte 'strukturierteDaten' enthalten, wenn angefordert");
        assertTrue(strukturierteDatenParam.getResource() instanceof Invoice, "'strukturierteDaten' sollte vom Typ Invoice sein");
        Invoice invoice = (Invoice) strukturierteDatenParam.getResource();
        assertNotNull(invoice.getId(), "Invoice sollte eine ID haben");

        // Stelle sicher, dass andere Teile nicht vorhanden sind, da nicht angefordert
        assertNull(result.getParameter("angereichertesPDF"), "Antwort sollte 'angereichertesPDF' nicht enthalten, da nicht angefordert.");
        assertNull(result.getParameter("originalPDF"), "Antwort sollte 'originalPDF' nicht enthalten, da nicht angefordert.");
        assertNull(result.getParameter("signatur"), "Antwort sollte 'signatur' nicht enthalten, da nicht angefordert.");
        
        LOGGER.info("Retrieve Operation Test - Fokus auf Metadaten und strukturierte Daten erfolgreich abgeschlossen");
    }
    
    @Test
    void testRetrieveOperationWithoutStructuredContent() {
        LOGGER.info("Starte Retrieve Operation Test ohne strukturierte Daten (nur Metadaten erwartet)");
        
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(false));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .withAdditionalHeader("scope", "invoiceDoc.r")
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");

        Parameters.ParametersParameterComponent metadataParam = result.getParameter("dokumentMetadaten");
        assertNotNull(metadataParam, "Antwort sollte 'dokumentMetadaten' enthalten");
        assertTrue(metadataParam.getResource() instanceof DocumentReference, "'dokumentMetadaten' sollte vom Typ DocumentReference sein");

        assertNull(result.getParameter("strukturierteDaten"), "Antwort sollte 'strukturierteDaten' nicht enthalten, da nicht angefordert.");
        assertNull(result.getParameter("angereichertesPDF"), "Antwort sollte 'angereichertesPDF' nicht enthalten, da nicht angefordert.");
        assertNull(result.getParameter("originalPDF"), "Antwort sollte 'originalPDF' nicht enthalten, da nicht angefordert.");
        assertNull(result.getParameter("signatur"), "Antwort sollte 'signatur' nicht enthalten, da nicht angefordert.");
        
        LOGGER.info("Retrieve Operation Test ohne strukturierte Daten erfolgreich abgeschlossen");
    }
    
    private String getPatientKvnr() {
        return testPatient.getIdentifier().stream()
            .filter(id -> "http://fhir.de/sid/gkv/kvid-10".equals(id.getSystem()))
            .map(Identifier::getValue)
            .findFirst()
            .orElse("A123456789");
    }

    @Test
    void testRetrieveOperationWithInvalidToken() {
        LOGGER.info("Starte Retrieve Operation Test mit ungültigem Token");
        
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType("invalid-token-for-test-12345"));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            client.operation()
                .onType(DocumentReference.class)
                .named("$retrieve")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .withAdditionalHeader("scope", "invoiceDoc.r")
                .execute();
        });
        
        // Prüfe auf die Standard HAPI FHIR Fehlermeldung für nicht gefundene Ressourcen
        assertTrue(exception.getMessage().contains("HAPI-2001: Resource") && exception.getMessage().contains("is not known"), 
            "Fehlermeldung sollte auf nicht gefundene Ressource hinweisen (HAPI-2001)");
            
        LOGGER.info("Retrieve Operation Test mit ungültigem Token erfolgreich abgeschlossen");
    }
    
    @Test
    void testRetrieveOperationWithoutToken() {
        LOGGER.info("Starte Retrieve Operation Test ohne Token");
        
        Parameters params = new Parameters();

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        UnprocessableEntityException exception = assertThrows(UnprocessableEntityException.class, () -> {
            client.operation()
                .onType(DocumentReference.class)
                .named("$retrieve")
                .withParameters(params)
                .withAdditionalHeader("Authorization", authHeader)
                .withAdditionalHeader("scope", "invoiceDoc.r")
                .execute();
        });
        
        assertTrue(exception.getMessage().contains("Token darf nicht leer sein"), 
            "Fehlermeldung sollte auf fehlendes Token hinweisen");
            
        LOGGER.info("Retrieve Operation Test ohne Token erfolgreich abgeschlossen");
    }
    
    @Test
    void testRetrieveOperationWithoutAuthorization() {
        LOGGER.info("Starte Retrieve Operation Test ohne Authorization");
        
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> {
            client.operation()
                .onType(DocumentReference.class)
                .named("$retrieve")
                .withParameters(params)
                .execute();
        });
        
        assertTrue(exception.getMessage().contains("Fehlender Authorization Header"), 
            "Fehlermeldung sollte auf fehlenden Access Token hinweisen");
            
        LOGGER.info("Retrieve Operation Test ohne Authorization erfolgreich abgeschlossen");
    }

    
    @Test
    void testRetrieveOperationWithVersichertenToken() {
        LOGGER.info("Starte Retrieve Operation Test mit Versicherten-Token (EGK1) - Anforderung aller Teile");
        
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(true));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(true));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(true));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(true));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .withAdditionalHeader("scope", "invoiceDoc.r")
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");
        assertNotNull(result.getParameter("dokumentMetadaten"), "Sollte 'dokumentMetadaten' enthalten.");
        assertNotNull(result.getParameter("strukturierteDaten"), "Sollte 'strukturierteDaten' enthalten.");
        assertNotNull(result.getParameter("originalPDF"), "Sollte 'originalPDF' enthalten.");
        assertNotNull(result.getParameter("angereichertesPDF"), "Sollte 'angereichertesPDF' enthalten.");
        assertNotNull(result.getParameter("signatur"), "Sollte 'signatur' enthalten und vom Typ Signature sein.");
        assertTrue(result.getParameter("signatur").getValue() instanceof Signature, "'signatur' sollte vom Typ Signature sein.");
        
        LOGGER.info("Retrieve Operation Test mit Versicherten-Token (Anforderung aller Teile) erfolgreich abgeschlossen");
    }
    
    @Test
    void testVAURetrieveOperation() throws Exception {
        if (vauClientCrypto == null) {
            LOGGER.warn("VAUClientCrypto nicht initialisiert, überspringe testVAURetrieveOperation.");
            return;
        }
        if (ergTokenForRetrieveTest == null || ergTokenForRetrieveTest.isEmpty()) {
            LOGGER.warn("ergTokenForRetrieveTest nicht initialisiert, überspringe testVAURetrieveOperation.");
            return;
        }
        LOGGER.info("Starte VAU Retrieve Operation Test mit ergToken: {}", ergTokenForRetrieveTest);
        
        String baseUrl = "http://localhost:" + port;
        LOGGER.info("Hole VAU-Zertifikat von: {}", baseUrl + "/VAUCertificate");
        
        ResponseEntity<byte[]> certResponse = new RestTemplate().exchange(
            baseUrl + "/VAUCertificate",
            HttpMethod.GET,
            null,
            byte[].class
        );
        
        assertEquals(HttpStatus.OK, certResponse.getStatusCode(), "VAU-Zertifikat-Abruf sollte erfolgreich sein");
        assertEquals(MediaType.parseMediaType("application/pkix-cert"), certResponse.getHeaders().getContentType(),
            "Content-Type sollte application/pkix-cert sein");
            
        byte[] certData = certResponse.getBody();
        assertNotNull(certData, "Zertifikatsdaten sollten nicht null sein");
        LOGGER.info("VAU-Zertifikat erfolgreich abgerufen, Größe: {} Bytes", certData.length);
        
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("EC");
        java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(certData);
        java.security.PublicKey publicKey = keyFactory.generatePublic(keySpec);

        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(true));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false));

        String requestBody = ctx.newJsonParser().encodeResourceToString(params);
        LOGGER.info("Request Body erstellt: {} Bytes", requestBody.length());
        
        String innerRequest = String.format(
            "POST /fhir/DocumentReference/$retrieve HTTP/1.1\r\n" +
            "Content-Type: application/fhir+json\r\n" +
            "Accept: application/fhir+json\r\n" +
            "scope: invoiceDoc.r\r\n" +
            "Content-Length: %d\r\n" +
            "\r\n" +
            "%s",
            requestBody.length(),
            requestBody
        );
        LOGGER.info("Inner Request erstellt: {} Bytes", innerRequest.length());

        String requestId = vauClientCrypto.generateRequestId();
        SecretKeySpec responseKey = vauClientCrypto.generateResponseKey();
        String responseKeyBase64 = Base64.getEncoder().encodeToString(responseKey.getEncoded());
        LOGGER.info("Response Key erstellt: {}", responseKeyBase64);

        String vauRequest = String.format("1 %s %s %s %s",
            getValidAccessToken("EGK1"),
            requestId,
            responseKeyBase64,
            innerRequest);
        LOGGER.info("VAU Request erstellt: {} Bytes", vauRequest.length());

        byte[] encryptedRequest = vauClientCrypto.encrypt(publicKey, vauRequest);
        LOGGER.info("Request verschlüsselt: {} Bytes", encryptedRequest.length);

        // Sende den Request an den VAU-Endpoint
        LOGGER.info("Sende Request an: {}", baseUrl + "/VAU/0");
        
        try {
            HttpHeaders vauHttpHeaders = createVAUHeaders(); // Basis-VAU-Header holen
            // Spezifische Header für diesen VAU-Request hinzufügen
            // Annahme: 'l' für Leistungserbringer/Arzt, 'v' für Versicherter, 'k' für Kostenträger etc.
            // Der genaue Wert für X-erp-user hängt von der Rolle des Access Tokens ab.
            // Hier wird basierend auf dem EGK1-Token "v" (Versicherter) angenommen.
            vauHttpHeaders.add("X-erp-user", "v"); 
            vauHttpHeaders.add("X-erp-resource", "DocumentReference"); // Die Ressource, auf die sich die Operation bezieht

            ResponseEntity<byte[]> response = new RestTemplate().exchange(
                baseUrl + "/VAU/0",
                HttpMethod.POST,
                new HttpEntity<>(encryptedRequest, vauHttpHeaders), // Verwende die erweiterten Header
                byte[].class
            );

            // Überprüfe die Response
            LOGGER.info("Response Status: {}", response.getStatusCode());
            LOGGER.info("Response Content-Type: {}", response.getHeaders().getContentType());
            LOGGER.info("Response Body Länge: {}", response.getBody() != null ? response.getBody().length : 0);
            
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
            assertNotNull(response.getHeaders().get("Userpseudonym"));

            String decryptedResponse = vauClientCrypto.decrypt(responseKey, response.getBody());
            LOGGER.info("Response entschlüsselt: {} Bytes", decryptedResponse.length());

            String[] responseParts = decryptedResponse.split(" ", 3);
            assertEquals("1", responseParts[0]);
            assertEquals(requestId, responseParts[1]);
            
            String httpResponse = responseParts[2];
            LOGGER.info("HTTP Response: {}", httpResponse);
            assertTrue(httpResponse.contains("HTTP/1.1 200 OK"));
            assertTrue(httpResponse.contains("Content-Type: application/fhir+json"));

            String responseBody = httpResponse.substring(httpResponse.indexOf("{"));
            Parameters result = ctx.newJsonParser().parseResource(Parameters.class, responseBody);

            assertNotNull(result, "Ergebnis sollte nicht null sein");
            Parameters.ParametersParameterComponent dokumentMetadatenParam = result.getParameter().stream()
                .filter(p -> "dokumentMetadaten".equals(p.getName()))
                .findFirst()
                .orElse(null);
            assertNotNull(dokumentMetadatenParam, "Antwort sollte 'dokumentMetadaten' enthalten");
            assertTrue(dokumentMetadatenParam.getResource() instanceof DocumentReference, "'dokumentMetadaten' sollte DocumentReference sein.");

            Parameters.ParametersParameterComponent strukturierteDatenParam = result.getParameter("strukturierteDaten");
            assertNotNull(strukturierteDatenParam, "Antwort sollte 'strukturierteDaten' enthalten, da angefordert.");
            assertTrue(strukturierteDatenParam.getResource() instanceof Invoice, "'strukturierteDaten' sollte Invoice sein.");

            assertNull(result.getParameter("angereichertesPDF"), "VAU-Antwort sollte 'angereichertesPDF' nicht enthalten.");
            assertNull(result.getParameter("originalPDF"), "VAU-Antwort sollte 'originalPDF' nicht enthalten.");
            assertNull(result.getParameter("signatur"), "VAU-Antwort sollte 'signatur' nicht enthalten.");
            
        } catch (Exception e) {
            LOGGER.error("Fehler beim VAU-Request: {}", e.getMessage(), e);
            throw e;
        }
    }

    protected HttpHeaders createVAUHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
        // X-erp-user und X-erp-resource werden jetzt spezifisch im Test gesetzt
        return headers;
    }

    @Test
    void testDownloadDocumentAndSavePdf() throws Exception {
        if (ergTokenForRetrieveTest == null || ergTokenForRetrieveTest.isEmpty()) {
            LOGGER.warn("ergTokenForRetrieveTest nicht initialisiert, überspringe testDownloadDocumentAndSavePdf.");
            // org.junit.jupiter.api.Assumptions.assumeTrue(false, "ergTokenForRetrieveTest nicht initialisiert.");
            return;
        }
        LOGGER.info("Starte Test zum Herunterladen des Dokuments und Speichern der PDF mit ergToken: {}", ergTokenForRetrieveTest);

        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(true));
        // Für diesen Test wollen wir sicherstellen, dass die originale Rechnung (falls vom Profil unterstützt)
        // und das angereicherte PDF angefordert werden, um alle Pfade zu testen.
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(true)); 
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(true));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false)); // Signatur nicht primärer Fokus hier

        String authHeader = "Bearer " + getValidAccessToken("EGK1"); // Passende Rolle für den Zugriff

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .withAdditionalHeader("scope", "invoiceDoc.r") // Scope, falls für die Operation benötigt
            .execute();

        assertNotNull(result, "Ergebnis der $retrieve Operation sollte nicht null sein");

        // Metadaten prüfen
        Parameters.ParametersParameterComponent metadataParam = result.getParameter("dokumentMetadaten");
        assertNotNull(metadataParam, "Antwort sollte einen 'dokumentMetadaten'-Parameter enthalten");
        assertTrue(metadataParam.getResource() instanceof DocumentReference, "'dokumentMetadaten'-Parameter sollte eine DocumentReference sein");
        
        // Strukturierte Daten (Invoice) prüfen
        Parameters.ParametersParameterComponent strukturierteDatenParam = result.getParameter("strukturierteDaten");
        assertNotNull(strukturierteDatenParam, "Antwort sollte 'strukturierteDaten' enthalten, wenn angefordert.");
        assertTrue(strukturierteDatenParam.getResource() instanceof Invoice, "'strukturierteDaten' sollte vom Typ Invoice sein.");
        Invoice savedInvoice = (Invoice) strukturierteDatenParam.getResource();
        assertNotNull(savedInvoice, "Invoice-Ressource (strukturierter Inhalt) sollte ladbar sein.");
         LOGGER.info("Strukturierte Invoice (ID: {}, Rechnungsnr.: {}) erfolgreich geladen.", 
            savedInvoice.getIdElement().toVersionless(), 
            savedInvoice.hasIdentifier() ? savedInvoice.getIdentifierFirstRep().getValue() : "N/A");


        // Angereichertes PDF prüfen und speichern
        Parameters.ParametersParameterComponent angereichertesPdfParam = result.getParameter("angereichertesPDF");
        assertNotNull(angereichertesPdfParam, "Antwort sollte 'angereichertesPDF' enthalten, wenn angefordert.");
        assertTrue(angereichertesPdfParam.getResource() instanceof Binary, "'angereichertesPDF' sollte eine Binary sein.");
        Binary angereichertesPdfBinary = (Binary) angereichertesPdfParam.getResource();

        assertNotNull(angereichertesPdfBinary, "Binary-Ressource für angereichertes PDF sollte nicht null sein");
        assertEquals("application/pdf", angereichertesPdfBinary.getContentType(), "Content-Type der Binary sollte PDF sein");
        assertTrue(angereichertesPdfBinary.hasData(), "Angereichertes PDF-Binary sollte Daten enthalten.");
        assertTrue(angereichertesPdfBinary.getData().length > 1000, "Angereicherte PDF-Daten sollten eine plausible Mindestgröße haben."); 

        java.nio.file.Path outputDir = java.nio.file.Paths.get("src", "test", "resources", "output");
        try {
            java.nio.file.Files.createDirectories(outputDir);
            java.nio.file.Path pdfPath = outputDir.resolve("retrieved_enriched_invoice_" + ergTokenForRetrieveTest + ".pdf");
            java.nio.file.Files.write(pdfPath, angereichertesPdfBinary.getData());
            LOGGER.info("Angereichertes PDF wurde erfolgreich gespeichert unter: {}", pdfPath.toAbsolutePath());
        } catch (java.io.IOException e) {
            LOGGER.error("Fehler beim Speichern der angereicherten PDF-Datei: {}", e.getMessage(), e);
            fail("Konnte angereicherte PDF nicht im Output-Verzeichnis speichern: " + e.getMessage());
        }

        try (PDDocument document = PDDocument.load(angereichertesPdfBinary.getData())) {
            assertNotNull(document, "Angereichertes PDF-Dokument sollte mit PDFBox ladbar sein.");
            assertTrue(document.getNumberOfPages() >= 1, "Angereichertes PDF sollte mindestens eine Seite haben.");
            LOGGER.info("Angereichertes PDF-Dokument erfolgreich mit PDFBox geladen, Seitenanzahl: {}.", document.getNumberOfPages());
        } catch (Exception e) {
            LOGGER.error("Fehler beim Laden des angereicherten PDFs mit PDFBox: {}", e.getMessage(), e);
            fail("Das heruntergeladene angereicherte PDF konnte nicht mit PDFBox geöffnet werden.", e);
        }

        // Original PDF prüfen und speichern (falls vorhanden)
        Parameters.ParametersParameterComponent originalPdfParam = result.getParameter("originalPDF");
        if (originalPdfParam != null) { // Original PDF ist optional
            assertTrue(originalPdfParam.getResource() instanceof Binary, "'originalPDF' sollte eine Binary sein.");
            Binary originalPdfBinary = (Binary) originalPdfParam.getResource();

            assertNotNull(originalPdfBinary, "Binary-Ressource für originales PDF sollte nicht null sein");
            assertEquals("application/pdf", originalPdfBinary.getContentType(), "Content-Type der Binary sollte PDF sein");
            assertTrue(originalPdfBinary.hasData(), "Originales PDF-Binary sollte Daten enthalten.");
            assertTrue(originalPdfBinary.getData().length > 1000, "Originale PDF-Daten sollten eine plausible Mindestgröße haben.");

            try {
                java.nio.file.Path pdfPath = outputDir.resolve("retrieved_original_invoice_" + ergTokenForRetrieveTest + ".pdf");
                java.nio.file.Files.write(pdfPath, originalPdfBinary.getData());
                LOGGER.info("Originales PDF wurde erfolgreich gespeichert unter: {}", pdfPath.toAbsolutePath());
            } catch (java.io.IOException e) {
                LOGGER.error("Fehler beim Speichern der originalen PDF-Datei: {}", e.getMessage(), e);
                fail("Konnte originales PDF nicht im Output-Verzeichnis speichern: " + e.getMessage());
            }

            try (PDDocument document = PDDocument.load(originalPdfBinary.getData())) {
                assertNotNull(document, "Originales PDF-Dokument sollte mit PDFBox ladbar sein.");
                assertTrue(document.getNumberOfPages() >= 1, "Originales PDF sollte mindestens eine Seite haben.");
                 LOGGER.info("Originales PDF-Dokument erfolgreich mit PDFBox geladen, Seitenanzahl: {}.", document.getNumberOfPages());
            } catch (Exception e) {
                LOGGER.error("Fehler beim Laden des originalen PDFs mit PDFBox: {}", e.getMessage(), e);
                fail("Das heruntergeladene originale PDF konnte nicht mit PDFBox geöffnet werden.", e);
            }
        } else {
            LOGGER.info("Kein 'originalPDF' in der Antwort enthalten oder nicht angefordert, Speicherung und Validierung übersprungen.");
        }
        
        LOGGER.info("Test zum Herunterladen des Dokuments und Speichern der PDF erfolgreich abgeschlossen.");
    }

    @Test
    void testRetrieveOperationWithErezeptScope() {
        if (ergTokenForRetrieveTest == null || ergTokenForRetrieveTest.isEmpty()) {
            LOGGER.warn("ergTokenForRetrieveTest nicht initialisiert, überspringe testRetrieveOperationWithErezeptScope.");
            return;
        }
        LOGGER.info("Starte Retrieve Operation Test mit openid e-rezept Scope und ergToken: {}", ergTokenForRetrieveTest);
        
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(true));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(true));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(true));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .withAdditionalHeader("scope", "openid e-rezept")
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");

        // Erweiterte Log-Ausgaben für das Ergebnisobjekt
        LOGGER.info("testRetrieveOperationWithErezeptScope: Empfangenes Result-Objekt (Typ: {}):\n{}", 
            result.fhirType(), 
            ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(result));
        
        if (result.hasParameter()) {
            LOGGER.info("testRetrieveOperationWithErezeptScope: Anzahl der Parameter im Result: {}", result.getParameter().size());
            result.getParameter().forEach(p -> {
                String paramName = p.getName();
                String paramType = p.getResource() != null ? p.getResource().fhirType() : (p.getValue() != null ? p.getValue().fhirType() : "N/A");
                LOGGER.info("testRetrieveOperationWithErezeptScope: Parameter Name: '{}', Typ: '{}'", paramName, paramType);
            });
        } else {
            LOGGER.info("testRetrieveOperationWithErezeptScope: Result-Objekt hat keine Parameter.");
        }

        Parameters.ParametersParameterComponent metadataParam = result.getParameter("dokumentMetadaten");
        assertNotNull(metadataParam, "Antwort sollte 'dokumentMetadaten' enthalten");
        assertTrue(metadataParam.getResource() instanceof DocumentReference, "Dokument sollte vom Typ DocumentReference sein");
        
        DocumentReference retrievedDoc = (DocumentReference) metadataParam.getResource();
        
        boolean hasStructuredContent = retrievedDoc.getContent().stream()
            .anyMatch(content -> 
                content.getFormat() != null &&
                "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(content.getFormat().getSystem()) &&
                "rechnungsinhalt".equals(content.getFormat().getCode())
            );
            
        assertTrue(hasStructuredContent, "Dokument sollte strukturierten Rechnungsinhalt enthalten");
        
        Parameters.ParametersParameterComponent signaturParam = result.getParameter("signatur");
        assertNotNull(signaturParam, "Antwort sollte 'signatur' enthalten");
        assertTrue(signaturParam.getValue() instanceof Signature, "'signatur' sollte vom Typ Signature sein.");
        
        LOGGER.info("Retrieve Operation Test mit openid e-rezept Scope erfolgreich abgeschlossen");
    }

    // Neue Tests für spezifische Output-Parameter

    @Test
    void testRetrieveOnlyAngereichertesPDF() {
        LOGGER.info("Starte Retrieve Test - Nur angereichertes PDF");
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(true));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(false));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");
        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");
        assertNotNull(result.getParameter("dokumentMetadaten"), "Sollte 'dokumentMetadaten' enthalten.");
        assertNotNull(result.getParameter("angereichertesPDF"), "Sollte 'angereichertesPDF' enthalten.");
        assertTrue(result.getParameter("angereichertesPDF").getResource() instanceof Binary, "'angereichertesPDF' sollte Binary sein.");
        assertNull(result.getParameter("strukturierteDaten"), "Sollte 'strukturierteDaten' NICHT enthalten.");
        assertNull(result.getParameter("originalPDF"), "Sollte 'originalPDF' NICHT enthalten.");
        assertNull(result.getParameter("signatur"), "Sollte 'signatur' NICHT enthalten.");
        LOGGER.info("Retrieve Test - Nur angereichertes PDF erfolgreich.");
    }

    @Test
    void testRetrieveOnlyOriginalPDF() {
        LOGGER.info("Starte Retrieve Test - Nur Original PDF");
        // Stelle sicher, dass ein Dokument mit Original-Referenz existiert.
        // Im BaseProviderTest wird testRechnungDocRef so erstellt, dass es eine relatesTo transform Referenz hat.

        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest)); // ergTokenForRetrieveTest verweist auf die *transformierte* (angereicherte) Version
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(true));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(false));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");
        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");
        assertNotNull(result.getParameter("dokumentMetadaten"), "Sollte 'dokumentMetadaten' enthalten.");
        assertNotNull(result.getParameter("originalPDF"), "Sollte 'originalPDF' enthalten.");
        assertTrue(result.getParameter("originalPDF").getResource() instanceof Binary, "'originalPDF' sollte Binary sein.");
        assertNull(result.getParameter("angereichertesPDF"), "Sollte 'angereichertesPDF' NICHT enthalten.");
        assertNull(result.getParameter("strukturierteDaten"), "Sollte 'strukturierteDaten' NICHT enthalten.");
        assertNull(result.getParameter("signatur"), "Sollte 'signatur' NICHT enthalten.");
        LOGGER.info("Retrieve Test - Nur Original PDF erfolgreich.");
    }

    @Test
    void testRetrieveOnlySignatur() {
        LOGGER.info("Starte Retrieve Test - Nur Signatur");
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(true));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(false));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");
        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");
        assertNotNull(result.getParameter("dokumentMetadaten"), "Sollte 'dokumentMetadaten' enthalten.");
        assertNotNull(result.getParameter("signatur"), "Sollte 'signatur' enthalten.");
        assertTrue(result.getParameter("signatur").getValue() instanceof Signature, "'signatur' sollte vom Typ Signature sein.");
        assertNull(result.getParameter("angereichertesPDF"), "Sollte 'angereichertesPDF' NICHT enthalten.");
        assertNull(result.getParameter("strukturierteDaten"), "Sollte 'strukturierteDaten' NICHT enthalten.");
        assertNull(result.getParameter("originalPDF"), "Sollte 'originalPDF' NICHT enthalten.");
        LOGGER.info("Retrieve Test - Nur Signatur erfolgreich.");
    }

    @Test
    void testRetrieveNoOptionalParameters() {
        LOGGER.info("Starte Retrieve Test - Keine optionalen Parameter (nur Metadaten)");
        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(false));
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false));

        String authHeader = "Bearer " + getValidAccessToken("EGK1");
        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");
        assertNotNull(result.getParameter("dokumentMetadaten"), "Sollte 'dokumentMetadaten' enthalten.");
        assertEquals(1, result.getParameter().size(), "Sollte nur den 'dokumentMetadaten' Parameter enthalten.");
        assertNull(result.getParameter("angereichertesPDF"));
        assertNull(result.getParameter("strukturierteDaten"));
        assertNull(result.getParameter("originalPDF"));
        assertNull(result.getParameter("signatur"));
        LOGGER.info("Retrieve Test - Keine optionalen Parameter erfolgreich.");
    }

    @Test
    void testRetrieveOperationAddsMarkierungExtension() {
        LOGGER.info("Starte Retrieve Operation Test - Fokus auf Markierung-Extension");

        Parameters params = new Parameters();
        params.addParameter().setName("token").setValue(new StringType(ergTokenForRetrieveTest));
        params.addParameter().setName("returnStrukturierteDaten").setValue(new BooleanType(false)); // Nicht relevant für diesen Test
        params.addParameter().setName("returnOriginalPDF").setValue(new BooleanType(false));     // Nicht relevant für diesen Test
        params.addParameter().setName("returnAngereichertesPDF").setValue(new BooleanType(false)); // Nicht relevant für diesen Test
        params.addParameter().setName("returnSignatur").setValue(new BooleanType(false));        // Nicht relevant für diesen Test

        String authHeader = "Bearer " + getValidAccessToken("EGK1"); // EGK1 als Beispiel

        Parameters result = client.operation()
            .onType(DocumentReference.class)
            .named("$retrieve")
            .withParameters(params)
            .withAdditionalHeader("Authorization", authHeader)
            .withAdditionalHeader("scope", "invoiceDoc.r") // oder "openid e-rezept"
            .execute();

        assertNotNull(result, "Ergebnis sollte nicht null sein");

        // Prüfe Metadaten und die Markierung-Extension
        Parameters.ParametersParameterComponent metadataParam = result.getParameter("dokumentMetadaten");
        assertNotNull(metadataParam, "Antwort sollte 'dokumentMetadaten' enthalten");
        assertTrue(metadataParam.getResource() instanceof DocumentReference, "'dokumentMetadaten' sollte vom Typ DocumentReference sein");
        DocumentReference retrievedMetadata = (DocumentReference) metadataParam.getResource();

        final String MARKIERUNG_MAIN_EXTENSION_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung";
        final String SUB_EXT_MARKIERUNG_URL = "markierung";
        final String SUB_EXT_GELESEN_URL = "gelesen";
        final String SUB_EXT_DETAILS_URL = "details";
        final String SUB_EXT_ZEITPUNKT_URL = "zeitpunkt";
        final String MARKIERUNG_CODING_SYSTEM_FOR_GELESEN = "https://gematik.de/fhir/erg/ValueSet/erg-dokument-artderarchivierung-vs";
        final String MARKIERUNG_CODE_GELESEN = "gelesen";

        // Haupt-Extension prüfen
        Extension markierungMainExtension = retrievedMetadata.getExtensionsByUrl(MARKIERUNG_MAIN_EXTENSION_URL).stream().findFirst().orElse(null);
        assertNotNull(markierungMainExtension, "DocumentReference sollte die Markierung-Haupt-Extension enthalten.");

        // Sub-Extension "markierung" (Typ der Markierung)
        Extension markierungTypSubExtension = markierungMainExtension.getExtensionsByUrl(SUB_EXT_MARKIERUNG_URL).stream().findFirst().orElse(null);
        assertNotNull(markierungTypSubExtension, "Markierung-Haupt-Extension sollte die 'markierung' Sub-Extension enthalten.");
        assertTrue(markierungTypSubExtension.getValue() instanceof Coding, "Wert der 'markierung' Sub-Extension sollte vom Typ Coding sein.");
        Coding markierungCoding = (Coding) markierungTypSubExtension.getValue();
        assertEquals(MARKIERUNG_CODING_SYSTEM_FOR_GELESEN, markierungCoding.getSystem(), "System des 'markierung' Codings ist nicht korrekt.");
        assertEquals(MARKIERUNG_CODE_GELESEN, markierungCoding.getCode(), "Code des 'markierung' Codings ist nicht korrekt.");

        // Sub-Extension "gelesen"
        Extension gelesenSubExtension = markierungMainExtension.getExtensionsByUrl(SUB_EXT_GELESEN_URL).stream().findFirst().orElse(null);
        assertNotNull(gelesenSubExtension, "Markierung-Haupt-Extension sollte die 'gelesen' Sub-Extension enthalten.");
        assertTrue(gelesenSubExtension.getValue() instanceof BooleanType, "Wert der 'gelesen' Sub-Extension sollte vom Typ BooleanType sein.");
        assertTrue(((BooleanType) gelesenSubExtension.getValue()).booleanValue(), "'gelesen' Sub-Extension sollte den Wert true haben.");

        // Sub-Extension "details"
        Extension detailsSubExtension = markierungMainExtension.getExtensionsByUrl(SUB_EXT_DETAILS_URL).stream().findFirst().orElse(null);
        assertNotNull(detailsSubExtension, "Markierung-Haupt-Extension sollte die 'details' Sub-Extension enthalten.");
        assertTrue(detailsSubExtension.getValue() instanceof StringType, "Wert der 'details' Sub-Extension sollte vom Typ StringType sein.");
        String detailsValue = ((StringType) detailsSubExtension.getValue()).getValue();
        assertNotNull(detailsValue, "Wert der 'details' Sub-Extension (String) darf nicht null sein.");
        assertFalse(detailsValue.isEmpty(), "Wert der 'details' Sub-Extension (String) darf nicht leer sein.");
        LOGGER.info("Details der Gelesen-Markierung: {}", detailsValue); // Loggen zur manuellen Überprüfung bei Bedarf

        // Sub-Extension "zeitpunkt"
        Extension zeitpunktSubExtension = markierungMainExtension.getExtensionsByUrl(SUB_EXT_ZEITPUNKT_URL).stream().findFirst().orElse(null);
        assertNotNull(zeitpunktSubExtension, "Markierung-Haupt-Extension sollte die 'zeitpunkt' Sub-Extension enthalten.");
        assertTrue(zeitpunktSubExtension.getValue() instanceof DateTimeType, "Wert der 'zeitpunkt' Sub-Extension sollte vom Typ DateTimeType sein.");
        assertNotNull(((DateTimeType) zeitpunktSubExtension.getValue()).getValue(), "Wert der 'zeitpunkt' Sub-Extension (Date) darf nicht null sein.");

        LOGGER.info("Retrieve Operation Test - Fokus auf Markierung-Extension erfolgreich abgeschlossen.");
    }
} 