package ca.uhn.fhir.jpa.starter.custom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled; // Import für @Disabled
import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Resource; // Sicherstellen, dass Resource importiert wird
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Import für Parameters hinzufügen
import org.hl7.fhir.r4.model.Parameters;

class GenerateTestResources {

    private static final Logger logger = LoggerFactory.getLogger(GenerateTestResources.class);
    private static final String BASE_OUTPUT_DIR = "src/test/resources/generated-test-resources";

    @Test
    @Disabled("Diesen Test nur manuell ausführen, um die Ressourcen bei Bedarf zu generieren.")
    void generateAllResources() {
        // Ruft die Methode auf, die alle Testressourcen generiert und als JSON speichert
        ErgTestResourceUtil.generateAndSaveAllTestResourcesAsJson();
    }

    @Test
    @Disabled("Diesen Test nur manuell ausführen, um spezifische Ressourcen für existierende IDs zu generieren.")
    void generateSpecificResourcesAndSave() {
        FhirContext ctx = FhirContext.forR4();
        // Neuer Unterordner für diese spezifische Generierung
        String specificOutputDirPath = Paths.get(BASE_OUTPUT_DIR, "specific-submission-set").toString();
        Path outputPath = Paths.get(specificOutputDirPath);

        // Beispiel-IDs (können für echte Tests angepasst werden)
        String patientId = "Patient/3b220526-575b-4bbb-b81f-d0520abb4ef9";
        String practitionerId = "Practitioner/99107c5e-cf3d-4314-99a0-118d14e855fb";
        String organizationId = "Organization/c583dc6b-fed5-4d9d-8aba-38460cce7670";

        try {
            Files.createDirectories(outputPath); // Sicherstellen, dass das Verzeichnis existiert

            ErgTestResourceUtil.GeneratedReferencingResources resources =
                ErgTestResourceUtil.generateResourcesForExistingEntities(patientId, practitionerId, organizationId);

            logger.info("Speichere spezifische Testressourcen...");

            if (resources.chargeItem != null) {
                ErgTestResourceUtil.saveResourceToJson(ctx, resources.chargeItem, outputPath.resolve("erg-chargeitem-specific.json"));
            }
            if (resources.invoice != null) {
                ErgTestResourceUtil.saveResourceToJson(ctx, resources.invoice, outputPath.resolve("erg-invoice-specific.json"));
                if (resources.invoicePdfData != null) {
                    Path pdfPath = outputPath.resolve("erg-invoice-specific.pdf");
                    Files.write(pdfPath, resources.invoicePdfData);
                    logger.info("Spezifisches Rechnungs-PDF erfolgreich gespeichert in: {}", pdfPath.toString());
                } else {
                    logger.warn("Keine PDF-Daten für spezifische Invoice vorhanden.");
                }
            }
            if (resources.attachmentDocumentReference != null) {
                ErgTestResourceUtil.saveResourceToJson(ctx, resources.attachmentDocumentReference, outputPath.resolve("erg-anhang-specific.json"));
            }
            if (resources.invoiceDocumentReference != null) {
                ErgTestResourceUtil.saveResourceToJson(ctx, resources.invoiceDocumentReference, outputPath.resolve("erg-documentreference-specific.json"));
            }

            logger.info("Spezifische Testressourcen wurden erfolgreich als JSON in '{}' gespeichert.", specificOutputDirPath);

            // Erstelle den Submit-Body als Parameters-Ressource
            Parameters submitParameters = new Parameters();
            if (resources.invoiceDocumentReference != null) {
                submitParameters.addParameter().setName("rechnung").setResource(resources.invoiceDocumentReference);
            }
            if (resources.attachmentDocumentReference != null) {
                submitParameters.addParameter().setName("anhang").setResource(resources.attachmentDocumentReference);
            }
            // Optional: Modus hinzufügen, falls gewünscht (Standard für die Operation)
            // submitParameters.addParameter().setName("modus").setValue(new CodeType("normal"));

            String submitBodyJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(submitParameters);
            logger.info("Generierter Submit-Body für $erechnung-submit:\n{}", submitBodyJson);

            // Speichere den Submit-Body JSON in einer Datei
            Path submitBodyFilePath = outputPath.resolve("erechnung-submit-body.json");
            Files.writeString(submitBodyFilePath, submitBodyJson);
            logger.info("Submit-Body JSON erfolgreich gespeichert in: {}", submitBodyFilePath.toString());

        } catch (IOException e) {
            logger.error("Fehler beim Generieren und Speichern der spezifischen Testressourcen: {}", e.getMessage(), e);
            // Den Test fehlschlagen lassen, um auf das Problem aufmerksam zu machen.
            throw new RuntimeException("Konnte spezifische Testressourcen nicht speichern", e);
        }
    }
} 