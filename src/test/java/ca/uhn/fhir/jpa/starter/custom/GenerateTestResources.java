package ca.uhn.fhir.jpa.starter.custom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled; // Import für @Disabled

class GenerateTestResources {

    @Test
    @Disabled("Diesen Test nur manuell ausführen, um die Ressourcen bei Bedarf zu generieren.")
    void generateAllResources() {
        // Ruft die Methode auf, die alle Testressourcen generiert und als JSON speichert
        ErgTestResourceUtil.generateAndSaveAllTestResourcesAsJson();
    }
} 