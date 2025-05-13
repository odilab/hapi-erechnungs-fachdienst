package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessTokenService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.PukTokenManager;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.TslManager;
import ca.uhn.fhir.jpa.starter.custom.config.TestcontainersConfig;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.json.JSONObject;
import ca.uhn.fhir.jpa.starter.Application;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.fail;
import static ca.uhn.fhir.jpa.starter.custom.ErgTestResourceUtil.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {
    Application.class, 
}, properties = {
    //"hapi.fhir.custom-bean-packages=ca.uhn.fhir.jpa.starter.custom.interceptor,ca.uhn.fhir.jpa.starter.custom.operation",
    "hapi.fhir.custom-interceptor-classes=ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AuthenticationInterceptor,ca.uhn.fhir.jpa.starter.custom.interceptor.auth.ResourceAuthorizationInterceptor",
    "hapi.fhir.custom-provider-classes=ca.uhn.fhir.jpa.starter.custom.operation.submit.SubmitOperationProvider,ca.uhn.fhir.jpa.starter.custom.operation.retrieve.RetrieveOperationProvider,ca.uhn.fhir.jpa.starter.custom.operation.processFlag.ProcessFlagOperationProvider,ca.uhn.fhir.jpa.starter.custom.operation.changeStatus.ChangeStatusOperationProvider,ca.uhn.fhir.jpa.starter.custom.operation.erase.EraseOperationProvider",
    "spring.datasource.url=jdbc:h2:mem:dbr4",
    "hapi.fhir.cr_enabled=false",
    "hapi.fhir.fhir_version=r4",
    "hapi.fhir.client_id_strategy=ANY"
})
@ContextConfiguration(initializers = {TestcontainersConfig.FullStackInitializer.class})
public abstract class BaseProviderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseProviderTest.class);

    @LocalServerPort
    protected int port;

    protected IGenericClient client;
    protected FhirContext ctx;
    protected Patient testPatient;
    protected Practitioner testPractitioner;
    protected Organization testInstitution;

    // Member-Variablen für Invoice-bezogene Testdaten
    protected ChargeItem testChargeItem;
    protected Invoice testInvoice;
    protected DocumentReference testAnhangDocRef;
    protected DocumentReference testRechnungDocRef;

    @Autowired
    protected AccessTokenService accessTokenService;

    @Autowired
    protected TslManager tslManager;
    
    @Autowired
    protected PukTokenManager pukTokenManager;

    // Speichere die KVNR aus dem EGK1-Token
    protected String versichertenKvnr;

    @BeforeEach
    protected void setUp() throws Exception {
        ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
        String ourServerBase = "http://localhost:" + port + "/fhir/";
        client = ctx.newRestfulGenericClient(ourServerBase);

        disableSSLValidation();

        accessTokenService.setSkipTimeValidation(false);
        accessTokenService.setSkipSignatureValidation(false);

        versichertenKvnr = extractKvnrFromEgk1Token();

        // 1. Initialisiere Basis-Test-Ressourcen lokal
        testPatient = createTestErgPatient();
        testPatient.getIdentifier().stream()
            .filter(id -> "http://fhir.de/sid/gkv/kvid-10".equals(id.getSystem()))
            .findFirst()
            .ifPresent(kvid -> kvid.setValue(versichertenKvnr));

        testPractitioner = createTestErgPractitioner();
        testInstitution = createTestErgInstitution();

        // 2. Speichere Basis-Ressourcen auf dem Server, um IDs zu erhalten
        saveBaseResourcesToServer();

        // 3. Initialisiere Invoice-bezogene Testdaten (nutzen die IDs der Basis-Ressourcen)
        initializeInvoiceTestData();
    }

    /**
     * Initialisiert die Testdaten, die für Invoice-bezogene Tests benötigt werden.
     */
    protected void initializeInvoiceTestData() {
        LOGGER.info("Initialisiere Invoice-bezogene Testdaten...");
        if (testPatient == null || testPractitioner == null || testInstitution == null) {
            fail("Basis-Test-Ressourcen (Patient, Practitioner, Institution) müssen vor Invoice-Daten initialisiert werden.");
        }

        try {
            // 1. Erstelle ChargeItem
            testChargeItem = createMinimalChargeItem(testPatient);
            LOGGER.debug("Test ChargeItem erstellt.");

            // 2. Erstelle Invoice
            testInvoice = createValidErgInvoice(testPatient, testPractitioner, testInstitution, testChargeItem);
            LOGGER.debug("Test Invoice erstellt.");

            // 3. Erstelle Anhang DocumentReference
            testAnhangDocRef = createTestAnhang(testPatient);
            LOGGER.debug("Test Anhang DocumentReference erstellt.");
            // Optional: Speichern für ID
            // testAnhangDocRef = (DocumentReference) client.create().resource(testAnhangDocRef).execute().getResource();
            String anhangId = testAnhangDocRef.getIdElement().getIdPart();
            if (anhangId == null) {
                anhangId = "anhang-dummy-" + System.currentTimeMillis(); // Eindeutige Dummy-ID
                testAnhangDocRef.setId(anhangId);
                LOGGER.warn("Anhang DocumentReference wurde nicht gespeichert. Verwende Dummy-ID: {}", anhangId);
            }

            // 4. Erstelle Haupt-DocumentReference (Rechnungs-Metadaten)
            // Der Aufruf von createValidErgDocumentReference generiert intern das PDF
            testRechnungDocRef = createValidErgDocumentReference(testPatient, testPractitioner, testInstitution, testInvoice, anhangId);
            LOGGER.debug("Test Rechnung DocumentReference erstellt (inkl. PDF).");
            // Optional: Speichern für ID
            // testRechnungDocRef = (DocumentReference) client.create().resource(testRechnungDocRef).execute().getResource();

            LOGGER.info("Invoice-bezogene Testdaten erfolgreich initialisiert.");

        } catch (Exception e) {
            LOGGER.error("Fehler bei der Initialisierung der Invoice-Testdaten: {}", e.getMessage(), e);
            fail("Fehler bei der Initialisierung der Invoice-Testdaten.", e);
        }
    }

    /**
     * Speichert die Basis-Testressourcen (Patient, Practitioner, Institution) auf dem FHIR-Server.
     * Stellt sicher, dass die Member-Variablen die vom Server zugewiesenen IDs enthalten.
     */
    protected void saveBaseResourcesToServer() {
        LOGGER.info("Speichere Basis-Testressourcen (Patient, Practitioner, Institution) auf dem Server...");
        try {
            // Verwende update statt create für Idempotenz. Funktioniert auch, wenn die Ressource noch nicht existiert.
            if (testPatient != null) {
                testPatient = (Patient) client.create()
                .resource(testPatient)
                .withAdditionalHeader("Authorization", "Bearer " + getValidAccessToken("EGK1"))
                .execute()
                .getResource();
                LOGGER.debug("Patient gespeichert/aktualisiert mit ID: {}", testPatient.getId());
            }
            if (testPractitioner != null) {
                testPractitioner = (Practitioner) client.create()
                .resource(testPractitioner)
                .withAdditionalHeader("Authorization", "Bearer " + getValidAccessToken("HBA_ARZT"))
                .execute()
                .getResource();
                LOGGER.debug("Practitioner gespeichert/aktualisiert mit ID: {}", testPractitioner.getId());
            }
            if (testInstitution != null) {
                testInstitution = (Organization) client.create()
                .resource(testInstitution)
                .withAdditionalHeader("Authorization", "Bearer " + getValidAccessToken("SMCB_KRANKENHAUS"))
                .execute()
                .getResource();
                LOGGER.debug("Institution gespeichert/aktualisiert mit ID: {}", testInstitution.getId());
            }
            LOGGER.info("Basis-Testressourcen erfolgreich auf dem Server gespeichert/aktualisiert.");
        } catch(Exception e) {
            LOGGER.error("Fehler beim Speichern der Basis-Testressourcen auf dem Server: {}", e.getMessage(), e);
            fail("Fehler beim Speichern der Basis-Testressourcen.", e);
        }
    }

    /**
     * Deaktiviert die SSL-Validierung für Testzwecke
     */
    protected void disableSSLValidation() {
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, new TrustManager[] { new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            LOGGER.info("SSL-Validierung deaktiviert");
        } catch (Exception e) {
            fail("Konnte SSL-Validierung nicht deaktivieren: " + e.getMessage());
        }
    }

    /**
     * Holt ein gültiges Access Token vom IDP Server
     * @param healthCardType Der Typ der Gesundheitskarte (z.B. "SMCB_KRANKENHAUS")
     * @return Das Access Token als String
     */
    protected String getValidAccessToken(String healthCardType) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format("https://%s:%d/getIdpToken?healthcards=%s",
                    TestcontainersConfig.startErpServiceContainer().getHost(),
                    TestcontainersConfig.startErpServiceContainer().getMappedPort(3001),
                    healthCardType));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("accept", "application/json");
            
            try (InputStream is = conn.getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                
                JSONObject jsonResponse = new JSONObject(response.toString());
                return jsonResponse.getString("accessToken");
            }
        } catch (Exception e) {
            LOGGER.error("Fehler beim Abrufen des Access Tokens: " + e.getMessage());
            throw new RuntimeException("Konnte Access Token nicht abrufen", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Extrahiert die KVNR aus einem EGK1-Token
     * @return Die KVNR des Versicherten
     */
    protected String extractKvnrFromEgk1Token() {
        String token = getValidAccessToken("EGK1");
        AccessToken accessToken = accessTokenService.verifyAndDecode("Bearer " + token);
        String kvnr = accessToken.getKvnr().orElse("A123456789");
        LOGGER.info("Extrahierte KVNR aus EGK1-Token: {}", kvnr);
        return kvnr;
    }

    /**
     * Kodiert Byte-Daten in Base64.
     * @param data Die zu kodierenden Daten.
     * @return Die Base64-kodierten Daten.
     */
    protected byte[] encodeToBase64(byte[] data) {
        return Base64.getEncoder().encode(data);
    }

    /**
     * Erstellt HTTP Header für VAU-Requests.
     * @return HttpHeaders Objekt.
     */
    protected HttpHeaders createVAUHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-erp-user", "l"); // Leistungserbringer
        headers.set("X-erp-resource", "Task");
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return headers;
    }
} 