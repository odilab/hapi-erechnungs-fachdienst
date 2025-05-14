package ca.uhn.fhir.jpa.starter.custom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled; // Import für @Disabled

class ErgTestResourceUtilTest {

    @Test
    @Disabled("Diesen Test nur manuell ausführen, um die Ressourcen bei Bedarf zu generieren.")
    void generateAllResources() {
        // Ruft die Methode auf, die alle Testressourcen generiert und als JSON speichert
        ErgTestResourceUtil.generateAndSaveAllTestResourcesAsJson();
        
        // Hier könnten noch Assertions hinzugefügt werden, um zu überprüfen, 
        // ob die Dateien tatsächlich erstellt wurden, falls gewünscht.
        // Zum Beispiel:
        // File outputDir = new File("src/test/resources/generated-test-resources");
        // assertTrue(outputDir.exists());
        // assertTrue(outputDir.isDirectory());
        // assertTrue(new File(outputDir, "erg-patient.json").exists());
        // ... usw. für andere Dateien
        
        // Für den reinen Generierungszweck ist ein leerer Testkörper nach dem Aufruf ausreichend.
    }
} 