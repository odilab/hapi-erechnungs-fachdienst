package ca.uhn.fhir.jpa.starter.custom.operation.vau;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.Application;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import ca.uhn.fhir.jpa.starter.custom.BaseProviderTest;

@SpringBootTest(
    classes = {Application.class}, // Stelle sicher, dass die Haupt-App-Klasse hier steht
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "hapi.fhir.rest.server_address=http://localhost:${local.server.port}/fhir"
})
class VAUOperationProviderTest extends BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(VAUOperationProviderTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private FhirContext ctx;

    private VAUClientCrypto vauClientCrypto;
    private RestTemplate restTemplate;
    private String baseUrl;

    @Override
    @BeforeEach
    protected void setUp() throws Exception {
        super.setUp();
        vauClientCrypto = new VAUClientCrypto();
        restTemplate = new RestTemplate();
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void testVAUCreatePatientOperation() throws Exception {
        LOGGER.info("Starte VAU Create Patient Operation Test");

        // 1. Hole das VAU-Zertifikat (Public Key)
        LOGGER.info("Hole VAU-Zertifikat von: {}", baseUrl + "/VAUCertificate");
        ResponseEntity<byte[]> certResponse = restTemplate.exchange(
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

        // Konvertiere die Zertifikatsdaten in einen Public Key
        KeyFactory keyFactory = KeyFactory.getInstance("EC"); // Oder RSA, je nachdem, was VAUServerCrypto verwendet
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(certData);
        PublicKey serverPublicKey = keyFactory.generatePublic(keySpec);
        LOGGER.info("Public Key erfolgreich generiert: {}", serverPublicKey.getAlgorithm());


        // 2. Erstelle die FHIR Patient Ressource
        Patient patientToCreate = new Patient();
        patientToCreate.addName().setFamily("VAU-Test").addGiven("Patient");
        patientToCreate.setActive(true);
        String patientJson = ctx.newJsonParser().encodeResourceToString(patientToCreate);
        LOGGER.info("Patient Ressource erstellt (JSON): {} Bytes", patientJson.length());


        // 3. Erstelle den inneren HTTP-Request
        String innerRequest = String.format(
            "POST /fhir/Patient HTTP/1.1\r\n" +
            "Host: localhost:%d\r\n" + // Host Header ist oft wichtig
            "Content-Type: application/fhir+json\r\n" +
            "Accept: application/fhir+json\r\n" +
            "Content-Length: %d\r\n" +
            "\r\n" +
            "%s",
            port,
            patientJson.getBytes().length, // Korrekte Länge verwenden
            patientJson
        );
        LOGGER.info("Inner Request erstellt: {} Bytes", innerRequest.length());


        // 4. Generiere VAU-Client-Daten
        String requestId = vauClientCrypto.generateRequestId();
        SecretKeySpec responseKey = vauClientCrypto.generateResponseKey();
        String responseKeyBase64 = Base64.getEncoder().encodeToString(responseKey.getEncoded());
        LOGGER.info("Request ID: {}, Response Key erstellt: {}", requestId, responseKeyBase64);

        // 5. Erstelle den VAU-Request-String mit einem GÜLTIGEN Token
        String validAccessToken = getValidAccessToken("SMCB_KRANKENHAUS"); // Gültigen Token holen
        assertNotNull(validAccessToken, "Konnte keinen gültigen Access Token erzeugen.");
        LOGGER.info("Gültiger Access Token für VAU-Request erzeugt.");

        String vauRequest = String.format("1 %s %s %s %s",
            validAccessToken, // Gültigen Token verwenden
            requestId,
            responseKeyBase64,
            innerRequest);
        LOGGER.info("VAU Request erstellt: {} Bytes", vauRequest.length());


        // 6. Verschlüssele den VAU-Request
        byte[] encryptedRequest = vauClientCrypto.encrypt(serverPublicKey, vauRequest);
        LOGGER.info("Request verschlüsselt: {} Bytes", encryptedRequest.length);


        // 7. Sende den verschlüsselten Request an den VAU-Endpoint
        LOGGER.info("Sende Request an: {}", baseUrl + "/VAU/0");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); // Content-Type des äußeren Requests
        headers.add("X-erp-user", "l"); // Beispielhafter User-Typ
        headers.add("X-erp-resource", "Patient"); // Beispielhafte Ressource

        HttpEntity<byte[]> requestEntity = new HttpEntity<>(encryptedRequest, headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
            baseUrl + "/VAU/0", // Start mit Userpseudonym "0"
            HttpMethod.POST,
            requestEntity,
            byte[].class
        );


        // 8. Verarbeite die verschlüsselte Antwort
        LOGGER.info("Response Status: {}", response.getStatusCode());
        LOGGER.info("Response Content-Type: {}", response.getHeaders().getContentType());
        LOGGER.info("Response Header 'Userpseudonym': {}", response.getHeaders().getFirst("Userpseudonym"));
        LOGGER.info("Response Body Länge: {}", response.getBody() != null ? response.getBody().length : 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        assertNotNull(response.getHeaders().getFirst("Userpseudonym"), "Userpseudonym Header sollte vorhanden sein");
        assertNotNull(response.getBody(), "Response Body sollte nicht null sein");

        // Entschlüssele die Antwort
        String decryptedResponse = vauClientCrypto.decrypt(responseKey, response.getBody());
        LOGGER.info("Response entschlüsselt: {} Bytes", decryptedResponse.length());


        // 9. Parse die entschlüsselte Antwort
        String[] responseParts = decryptedResponse.split(" ", 3);
        assertEquals(3, responseParts.length, "Entschlüsselte Antwort sollte 3 Teile haben");
        assertEquals("1", responseParts[0], "Version in der Antwort sollte '1' sein");
        assertEquals(requestId, responseParts[1], "Request ID in der Antwort sollte übereinstimmen");

        // 10. Parse die innere HTTP-Response
        String httpResponse = responseParts[2];
        LOGGER.info("Innere HTTP Response:\n{}", httpResponse);

        // Extrahiere Statuszeile und Header
        String[] responseLines = httpResponse.split("\r\n", 2); // Trenne Statuszeile vom Rest
        String statusLine = responseLines[0];
        assertTrue(statusLine.startsWith("HTTP/1.1 201 Created"), "Innerer Status sollte '201 Created' sein. War: " + statusLine);

        // Suche nach dem Location Header und Body
        String headersAndBodyPart = responseLines.length > 1 ? responseLines[1] : "";
        String[] headersAndBody = headersAndBodyPart.split("\r\n\r\n", 2); // Trenne Header vom Body
        String headersPart = headersAndBody[0];
        String responseBody = (headersAndBody.length > 1) ? headersAndBody[1].trim() : "";

        String locationHeader = null;
        for (String headerLine : headersPart.split("\r\n")) {
            if (headerLine.toLowerCase().startsWith("location:")) {
                locationHeader = headerLine.substring("location:".length()).trim();
                break;
            }
        }
        assertNotNull(locationHeader, "Location Header sollte in der inneren Antwort vorhanden sein");
        LOGGER.info("Location Header gefunden: {}", locationHeader);
        assertTrue(locationHeader.contains("/fhir/Patient/"), "Location Header sollte auf eine Patient Ressource verweisen");
        assertTrue(locationHeader.contains("/_history/"), "Location Header sollte eine Versions-ID enthalten");

        // Optional: Parse den Body der inneren Response (falls vorhanden und benötigt)
        if (!responseBody.isEmpty()) {
             LOGGER.info("Innerer Response Body:\n{}", responseBody);
             // Hier könntest du den Body parsen, z.B. als OperationOutcome oder die erstellte Ressource
             // Patient createdPatient = ctx.newJsonParser().parseResource(Patient.class, responseBody);
             // assertNotNull(createdPatient);
        } else {
            LOGGER.info("Innerer Response Body ist leer.");
        }

        LOGGER.info("VAU Create Patient Operation Test erfolgreich abgeschlossen.");
    }
} 