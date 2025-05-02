package ca.uhn.fhir.jpa.starter.custom.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.custom.ErgTestResourceUtil;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
    "hapi.fhir.custom-bean-packages=ca.uhn.fhir.jpa.starter.custom.interceptor",
    "hapi.fhir.custom-interceptor-classes=ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator",
    "spring.datasource.url=jdbc:h2:mem:dbr4",
    "hapi.fhir.cr_enabled=false",
    // "hapi.fhir.enable_repository_validating_interceptor=true",
    "hapi.fhir.fhir_version=r4"
})
class CustomValidatorTest  {

    private static final Logger logger = LoggerFactory.getLogger(CustomValidatorTest.class);

    @Autowired
    private CustomValidator validator;

    @LocalServerPort
    protected int port;

    protected IGenericClient client;
    protected FhirContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
        String ourServerBase = "http://localhost:" + port + "/fhir/";
        client = ctx.newRestfulGenericClient(ourServerBase);

    }

    @Test
    @DisplayName("Validierung einer gültigen Rechnung nach Gematik-Beispiel sollte erfolgreich sein")
    void testValidateValidDocumentReferenceGematikExample() {
        DocumentReference rechnung = new DocumentReference();
        rechnung.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        
        // Type gemäß Gematik-Beispiel
        CodeableConcept type = new CodeableConcept();
        Coding typeCoding = type.addCoding();
        typeCoding.setSystem("http://dvmd.de/fhir/CodeSystem/kdl");
        typeCoding.setCode("AM010106");
        typeCoding.setDisplay("Rechnung ambulante/stationäre Behandlung");
        rechnung.setType(type);
        
        // Description
        rechnung.setDescription("Rechnung Reiseimpfung vom 10.01.2024");
        
        // Subject (Patient)
        Identifier patientIdentifier = new Identifier();
        patientIdentifier.setSystem("http://fhir.de/sid/gkv/kvid-10");
        patientIdentifier.setValue("A000000000");
        rechnung.getSubject().setIdentifier(patientIdentifier);
        
        // Content mit PDF und XML Anhängen
        byte[] dummyData = "TESTDATEN".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(dummyData);
        
        // PDF Dokument
        DocumentReference.DocumentReferenceContentComponent pdfContent = rechnung.addContent();
        Attachment pdfAttachment = new Attachment();
        pdfAttachment.setContentType("application/pdf");
        pdfAttachment.setData(dummyData);
        pdfContent.setAttachment(pdfAttachment);
        
        // XRechnung
        DocumentReference.DocumentReferenceContentComponent xRechnungContent = rechnung.addContent();
        xRechnungContent.getFormat().setCode("xrechnung");
        Attachment xRechnungAttachment = new Attachment();
        xRechnungAttachment.setContentType("application/xml");
        xRechnungAttachment.setData(dummyData);
        xRechnungContent.setAttachment(xRechnungAttachment);
        
        // Gematik E-Rechnung
        DocumentReference.DocumentReferenceContentComponent eRechnungContent = rechnung.addContent();
        eRechnungContent.getFormat().setCode("gematik-erechnung");
        Attachment eRechnungAttachment = new Attachment();
        eRechnungAttachment.setContentType("application/fhir+xml");
        eRechnungAttachment.setData(dummyData);
        eRechnungContent.setAttachment(eRechnungAttachment);
        
        // Signatur Extension
        Extension signaturExtension = new Extension("http://example.org/StructureDefinition/signatur");
        Signature signature = new Signature();
        Coding signatureType = new Coding();
        signatureType.setSystem("urn:iso-astm:E1762-95:2013");
        signatureType.setCode("1.2.840.10065.1.12.1.1");
        signatureType.setDisplay("Author's Signature");
        signature.addType(signatureType);
        DateTimeType dateTime = new DateTimeType("2015-02-07T13:28:17.239+02:00");
        signature.setWhen(dateTime.getValue());
        Reference whoReference = new Reference();
        whoReference.setDisplay("Arzt");
        signature.setWho(whoReference);
        signaturExtension.setValue(signature);
        rechnung.addExtension(signaturExtension);
        
        // Sollte keine Exception werfen
        assertDoesNotThrow(() -> validator.validateResourceCreate(rechnung));
    }


    @Test
    @DisplayName("Alle StructureDefinitions sollten erfolgreich geladen werden")
    void testAllStructureDefinitionsLoaded() {
        // Prüfen, ob die ValidationSupportChain StructureDefinitions enthält
        var structureDefinitions = validator.getValidationSupportChain().fetchAllStructureDefinitions();
        assertFalse(structureDefinitions.isEmpty(), "Es wurden keine StructureDefinitions geladen");
        
        // Logge alle geladenen StructureDefinitions
        structureDefinitions.forEach(sd -> {
            StructureDefinition structDef = (StructureDefinition) sd;
            logger.info("Geladene StructureDefinition: {} ({})", structDef.getUrl(), structDef.getName());
        });
        
        // Prüfe, ob wichtige StructureDefinitions vorhanden sind
        assertTrue(
            structureDefinitions.stream()
                .anyMatch(sd -> "https://gematik.de/fhir/erg/StructureDefinition/erg-dokumentenmetadaten"
                    .equals(((StructureDefinition)sd).getUrl())),
            "erg-dokumentenmetadaten StructureDefinition wurde nicht gefunden"
        );
    }

    @Test
    @DisplayName("Alle FHIR-Ressourcen sollten erfolgreich geladen werden")
    void testAllResourcesLoaded() {
        // Prüfen, ob die ValidationSupportChain StructureDefinitions enthält
        var structureDefinitions = validator.getValidationSupportChain().fetchAllStructureDefinitions();
        assertFalse(structureDefinitions.isEmpty(), "Es wurden keine StructureDefinitions geladen");
        
        // Logge alle geladenen StructureDefinitions
        structureDefinitions.forEach(sd -> {
            StructureDefinition structDef = (StructureDefinition) sd;
            logger.info("Geladene StructureDefinition: {} ({})", structDef.getUrl(), structDef.getName());
        });
        
        // Prüfe, ob wichtige StructureDefinitions vorhanden sind
        assertTrue(
            structureDefinitions.stream()
                .anyMatch(sd -> "https://gematik.de/fhir/erg/StructureDefinition/erg-dokumentenmetadaten"
                    .equals(((StructureDefinition)sd).getUrl())),
            "erg-dokumentenmetadaten StructureDefinition wurde nicht gefunden"
        );

        // Prüfe ValueSets
        var valueSets = validator.getValidationSupportChain().fetchValueSet("https://gematik.de/fhir/erg/ValueSet/erg-rechnungsstatus-vs");
        assertNotNull(valueSets, "Das ValueSet erg-rechnungsstatus-vs wurde nicht gefunden");
        logger.info("ValueSet gefunden: {} ({})", ((ValueSet)valueSets).getUrl(), ((ValueSet)valueSets).getName());

        // Prüfe CodeSystem
        var codeSystem = validator.getValidationSupportChain().fetchCodeSystem("https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs");
        assertNotNull(codeSystem, "Das CodeSystem erg-attachment-format-cs wurde nicht gefunden");
        logger.info("CodeSystem gefunden: {} ({})", ((CodeSystem)codeSystem).getUrl(), ((CodeSystem)codeSystem).getName());
    }


    @Test
    @DisplayName("Validierung eines Patienten nach ERG-Profil sollte erfolgreich sein")
    void testValidateErgPatient() {
        Patient patient = ErgTestResourceUtil.createTestErgPatient();
        assertDoesNotThrow(() -> validator.validateResourceCreate(patient), "Validierung des ERG-Patienten sollte erfolgreich sein.");
    }

    @Test
    @DisplayName("Validierung eines Practitioners nach ERG-Person-Profil sollte erfolgreich sein")
    void testValidateErgPractitioner() {
        Practitioner practitioner = ErgTestResourceUtil.createTestErgPractitioner();
        assertDoesNotThrow(() -> validator.validateResourceCreate(practitioner), "Validierung des ERG-Practitioners sollte erfolgreich sein.");
    }

    @Test
    @DisplayName("Validierung einer Institution nach ERG-Profil sollte erfolgreich sein")
    void testValidateErgInstitution() {
        Organization institution = ErgTestResourceUtil.createTestErgInstitution();
        assertDoesNotThrow(() -> validator.validateResourceCreate(institution), "Validierung der ERG-Institution sollte erfolgreich sein.");
    }

    @Test
    @DisplayName("Validierung einer gültigen ERG-Rechnung sollte erfolgreich sein")
    void testValidateValidErgInvoice() {
        // 1. Referenzierte Ressourcen erstellen
        Patient ergPatient = ErgTestResourceUtil.createTestErgPatient();
        Practitioner ergPractitioner = ErgTestResourceUtil.createTestErgPractitioner();
        Organization ergInstitution = ErgTestResourceUtil.createTestErgInstitution();
        // Minimales ChargeItem für die Referenz in der Invoice
        ChargeItem chargeItem = ErgTestResourceUtil.createMinimalChargeItem(ergPatient); 

        // 2. Referenzierte Ressourcen auf dem Server speichern (benötigt gültige Tokens)
        // Speichere Patient (EGK1 Token)
        ergPatient = (Patient) client.create()
            .resource(ergPatient)
            .execute()
            .getResource();
        final String patientId = ergPatient.getIdElement().getIdPart();

        // Speichere Practitioner (HBA_ARZT Token)
        ergPractitioner = (Practitioner) client.create()
            .resource(ergPractitioner)
            .execute()
            .getResource();
        final String practitionerId = ergPractitioner.getIdElement().getIdPart();
        
        // Speichere Institution (SMC-B Token oder alternativ HBA_ARZT)
        ergInstitution = (Organization) client.create()
            .resource(ergInstitution)
            .execute()
            .getResource();
        final String institutionId = ergInstitution.getIdElement().getIdPart();

        // Speichere ChargeItem (HBA_ARZT Token - Annahme: Leistungserbringer erstellt ChargeItem)
        chargeItem.setSubject(new Reference("Patient/" + patientId)); // Subject aktualisieren mit gespeicherter ID
        chargeItem = (ChargeItem) client.create()
            .resource(chargeItem)
            .execute()
            .getResource();
        final String chargeItemId = chargeItem.getIdElement().getIdPart();

        // 2.5 Dummy-Anhang DocumentReference erstellen und speichern (Wieder hinzugefügt für Beispiel)
        DocumentReference dummyAnhangDocRef = new DocumentReference();
        dummyAnhangDocRef.getMeta().addProfile("http://hl7.org/fhir/StructureDefinition/DocumentReference");
        dummyAnhangDocRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        dummyAnhangDocRef.getType().addCoding().setSystem("http://loinc.org").setCode("11488-4"); // Beispiel-Typ
        dummyAnhangDocRef.addContent().getAttachment().setContentType("text/plain").setData("Dummy Anhang Inhalt".getBytes());
        dummyAnhangDocRef.setSubject(new Reference("Patient/" + patientId)); // Subject hinzufügen

        DocumentReference storedDummyAnhangDocRef = (DocumentReference) client.create()
            .resource(dummyAnhangDocRef)
            .execute()
            .getResource();
        final String anhangDocRefId = storedDummyAnhangDocRef.getIdElement().getIdPart();
        logger.info("Dummy Anhang DocumentReference gespeichert mit ID: {}", anhangDocRefId);

        // 3. ERG-Invoice erstellen (wird eingebettet, nicht separat gespeichert)
        Invoice ergInvoice = ErgTestResourceUtil.createValidErgInvoice(ergPatient, ergPractitioner, ergInstitution, chargeItem);

        // 4. ERG-DocumentReference erstellen, die auf die gespeicherten Ressourcen und die Invoice verweist
        DocumentReference ergDocRef = ErgTestResourceUtil.createValidErgDocumentReference(ergPatient, ergPractitioner, ergInstitution, ergInvoice, anhangDocRefId);

        // Ausgabe der erstellten DocumentReference vor dem Speichern
        String ergDocRefJson = FhirContext.forR4().newJsonParser().setPrettyPrint(true).encodeResourceToString(ergDocRef);
        // logger.info("Erstellte ERG-DocumentReference (vor Speicherung):\n{}", ergDocRefJson); // Ersetzt durch System.out
        System.out.println("--- Erstellte ERG-DocumentReference (vor Speicherung): ---");
        System.out.println(ergDocRefJson);
        System.out.println("-----------------------------------------------------------");

        // 5. DocumentReference auf dem Server speichern (löst die Validierung via Hook aus)
        assertDoesNotThrow(() -> {
            client.create()
                .resource(ergDocRef)
                // Annahme: Der Leistungserbringer (z.B. Arzt mit HBA) ist berechtigt, die Metadaten zu speichern
                .execute();
        }, "Validierung und Speicherung der ERG-DocumentReference sollte erfolgreich sein.");
    }

//    @Test
//    @DisplayName("Validiere alle geladenen StructureDefinitions als Ressourcen")
//    void testValidateLoadedStructureDefinitions() {
//        logger.info("Starte Test zur Validierung geladener StructureDefinitions...");
//
//        // 1. Hole die ValidationSupportChain aus dem CustomValidator
//        ValidationSupportChain supportChain = validator.getValidationSupportChain();
//        assertNotNull(supportChain, "ValidationSupportChain sollte nicht null sein.");
//
//        // 2. Hole alle StructureDefinitions, die vom Support geladen wurden
//        List<IBaseResource> structureDefinitions = supportChain.fetchAllStructureDefinitions();
//        assertNotNull(structureDefinitions, "Liste der StructureDefinitions sollte nicht null sein.");
//        assertFalse(structureDefinitions.isEmpty(), "Keine StructureDefinitions zum Validieren gefunden in der Support Chain.");
//        logger.info("Anzahl gefundener StructureDefinitions in der Chain: {}", structureDefinitions.size());
//
//        // 3. Iteriere durch die Liste und validiere jede StructureDefinition
//        int validationCount = 0;
//        for (IBaseResource resource : structureDefinitions) {
//            // Stelle sicher, dass es wirklich eine StructureDefinition ist
//            if (resource instanceof StructureDefinition) {
//                StructureDefinition sd = (StructureDefinition) resource;
//
//                final String sdUrl = sd.getUrl() != null ? sd.getUrl() : "Unbekannte URL (ID: " + sd.getIdElement().getIdPart() + ")";
//                logger.info("Validiere StructureDefinition: {}", sdUrl);
//
//                // 4. Rufe die Validierungsmethode des CustomValidators auf
//                assertDoesNotThrow(() -> {
//                    validator.validateAndThrowIfInvalid(sd); // Nutzt die bestehende Validierungslogik
//                }, "Validierung fehlgeschlagen für StructureDefinition: " + sdUrl);
//
//                validationCount++;
//
//            } else {
//                logger.warn("Gefundenes IBaseResource in fetchAllStructureDefinitions ist keine StructureDefinition: {}", resource.getClass().getName());
//            }
//        }
//
//        assertTrue(validationCount > 0, "Es wurde keine StructureDefinition tatsächlich validiert.");
//        logger.info("Erfolgreich {} StructureDefinitions als Ressourcen validiert.", validationCount);
//    }

	@Test
	@DisplayName("Validierung einer gültigen ERG-DocumentReference (Dokumentenmetadaten) sollte erfolgreich sein")
	void testValidateValidErgDocumentReference() {
		// 1. Referenzierte Ressourcen erstellen
		Patient ergPatient = ErgTestResourceUtil.createTestErgPatient();
		Practitioner ergPractitioner = ErgTestResourceUtil.createTestErgPractitioner();
		Organization ergInstitution = ErgTestResourceUtil.createTestErgInstitution();
		ChargeItem chargeItem = ErgTestResourceUtil.createMinimalChargeItem(ergPatient); // Für die Invoice benötigt

		// 2. Referenzierte Ressourcen auf dem Server speichern (benötigt gültige Tokens)
		// Speichere Patient (EGK1 Token)
		ergPatient = (Patient) client.create()
			.resource(ergPatient)
			.execute()
			.getResource();
		final String patientId = ergPatient.getIdElement().getIdPart();

		// Speichere Practitioner (HBA_ARZT Token)
		ergPractitioner = (Practitioner) client.create()
			.resource(ergPractitioner)
			.execute()
			.getResource();
		final String practitionerId = ergPractitioner.getIdElement().getIdPart();

		// Speichere Institution (SMC-B Token oder alternativ HBA_ARZT)
		ergInstitution = (Organization) client.create()
			.resource(ergInstitution)
			.execute()
			.getResource();
		final String institutionId = ergInstitution.getIdElement().getIdPart();

		// Speichere ChargeItem (HBA_ARZT Token - Annahme: Leistungserbringer erstellt ChargeItem)
		chargeItem.setSubject(new Reference("Patient/" + patientId)); // Subject aktualisieren mit gespeicherter ID
		chargeItem = (ChargeItem) client.create()
			.resource(chargeItem)
			.execute()
			.getResource();
		final String chargeItemId = chargeItem.getIdElement().getIdPart();

		// 2.5 Dummy-Anhang DocumentReference erstellen und speichern (Wieder hinzugefügt für Beispiel)
		DocumentReference dummyAnhangDocRef = new DocumentReference();
		dummyAnhangDocRef.getMeta().addProfile("http://hl7.org/fhir/StructureDefinition/DocumentReference");
		dummyAnhangDocRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
		dummyAnhangDocRef.getType().addCoding().setSystem("http://loinc.org").setCode("11488-4"); // Beispiel-Typ
		dummyAnhangDocRef.addContent().getAttachment().setContentType("text/plain").setData("Dummy Anhang Inhalt".getBytes());
		dummyAnhangDocRef.setSubject(new Reference("Patient/" + patientId)); // Subject hinzufügen

		DocumentReference storedDummyAnhangDocRef = (DocumentReference) client.create()
			.resource(dummyAnhangDocRef)
			.execute()
			.getResource();
		final String anhangDocRefId = storedDummyAnhangDocRef.getIdElement().getIdPart();
		logger.info("Dummy Anhang DocumentReference gespeichert mit ID: {}", anhangDocRefId);

		// 3. ERG-Invoice erstellen (wird eingebettet, nicht separat gespeichert)
		Invoice ergInvoice = ErgTestResourceUtil.createValidErgInvoice(ergPatient, ergPractitioner, ergInstitution, chargeItem);

		// 4. ERG-DocumentReference erstellen, die auf die gespeicherten Ressourcen und die Invoice verweist
		DocumentReference ergDocRef = ErgTestResourceUtil.createValidErgDocumentReference(ergPatient, ergPractitioner, ergInstitution, ergInvoice, anhangDocRefId);

		// Ausgabe der erstellten DocumentReference vor dem Speichern
		String ergDocRefJson = FhirContext.forR4().newJsonParser().setPrettyPrint(true).encodeResourceToString(ergDocRef);
		logger.info("Erstellte ERG-DocumentReference (vor Speicherung):\n{}", ergDocRefJson);

		// 5. DocumentReference auf dem Server speichern (löst die Validierung via Hook aus)
		assertDoesNotThrow(() -> {
			client.create()
				.resource(ergDocRef)
				// Annahme: Der Leistungserbringer (z.B. Arzt mit HBA) ist berechtigt, die Metadaten zu speichern
				.execute();
		}, "Validierung und Speicherung der ERG-DocumentReference sollte erfolgreich sein.");
	}

	@Test
	@DisplayName("Validierung einer ERG-Rechnungs-DocumentReference ohne Signatur-Extension sollte fehlschlagen")
	void testValidateErgDocumentReference_MissingSignatureConstraint() {
		// 1. Referenzierte Ressourcen erstellen
		Patient ergPatient = ErgTestResourceUtil.createTestErgPatient();
		Practitioner ergPractitioner = ErgTestResourceUtil.createTestErgPractitioner();
		Organization ergInstitution = ErgTestResourceUtil.createTestErgInstitution();
		ChargeItem chargeItem = ErgTestResourceUtil.createMinimalChargeItem(ergPatient); // Für die Invoice benötigt

		// 2. Referenzierte Ressourcen auf dem Server speichern
		// (IDs werden für die Invoice benötigt, die eingebettet wird)
		ergPatient = (Patient) client.create().resource(ergPatient).execute().getResource();
		ergPractitioner = (Practitioner) client.create().resource(ergPractitioner).execute().getResource();
		ergInstitution = (Organization) client.create().resource(ergInstitution).execute().getResource();
		chargeItem.setSubject(new Reference("Patient/" + ergPatient.getIdElement().getIdPart()));
		chargeItem = (ChargeItem) client.create().resource(chargeItem).execute().getResource();

		// 2.5 Dummy-Anhang DocumentReference erstellen und speichern (Wieder hinzugefügt für Beispiel)
		DocumentReference dummyAnhangDocRef = new DocumentReference();
		dummyAnhangDocRef.getMeta().addProfile("http://hl7.org/fhir/StructureDefinition/DocumentReference");
		dummyAnhangDocRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
		dummyAnhangDocRef.getType().addCoding().setSystem("http://loinc.org").setCode("11488-4");
		dummyAnhangDocRef.addContent().getAttachment().setContentType("text/plain").setData("Dummy Anhang Inhalt".getBytes());
		dummyAnhangDocRef.setSubject(new Reference("Patient/" + ergPatient.getIdElement().getIdPart())); // Subject hinzufügen

		DocumentReference storedDummyAnhangDocRef = (DocumentReference) client.create()
			.resource(dummyAnhangDocRef)
			.execute()
			.getResource();
		final String anhangDocRefId = storedDummyAnhangDocRef.getIdElement().getIdPart(); // HIER wird die Variable deklariert
		logger.info("Dummy Anhang DocumentReference gespeichert mit ID (Constraint Test): {}", anhangDocRefId);

		// 3. ERG-Invoice erstellen
		Invoice ergInvoice = ErgTestResourceUtil.createValidErgInvoice(ergPatient, ergPractitioner, ergInstitution, chargeItem);

		// 4. ERG-DocumentReference erstellen (zunächst valide)
		// Patient und Anhang-ID für context.related übergeben
		DocumentReference ergDocRef = ErgTestResourceUtil.createValidErgDocumentReference(ergPatient, ergPractitioner, ergInstitution, ergInvoice, anhangDocRefId);

		// 5. ABSICHTLICH UNGÜLTIG MACHEN: Entferne die Signatur-Extension
		// Da der Typ KDL AM010106 ist, sollte dies die 'SignaturVerpflichtendRechnung'-Constraint verletzen.
		boolean removed = ergDocRef.getExtension().removeIf(ext ->
			"https://gematik.de/fhir/erg/StructureDefinition/erg-docref-signature".equals(ext.getUrl()));

		if (!removed) {
			fail("Could not find and remove the signature extension to invalidate the DocumentReference.");
		}
		logger.info("Intentionally removed signature extension for test {}", ergDocRef.getId());


		// 6. Versuche, die ungültige DocumentReference zu speichern
		// Erwarte eine UnprocessableEntityException vom CustomValidator wegen verletzter Constraint
		assertThrows(UnprocessableEntityException.class, () -> { // Wieder client.create() im assertThrows
			client.create()
				.resource(ergDocRef)
				.execute();
		}, "Speichern einer ERG-Rechnungs-DocumentReference ohne Signatur sollte fehlschlagen.");
	}
}