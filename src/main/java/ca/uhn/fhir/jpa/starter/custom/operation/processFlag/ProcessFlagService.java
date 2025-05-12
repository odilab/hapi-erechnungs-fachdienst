package ca.uhn.fhir.jpa.starter.custom.operation.processFlag;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Date;

@Service
public class ProcessFlagService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessFlagService.class);

    private final DaoRegistry daoRegistry;

    @Autowired
    public ProcessFlagService(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    /**
     * Fügt die angegebene Markierung zum DocumentReference hinzu und speichert es.
     *
     * @param document           Das zu modifizierende DocumentReference.
     * @param markierung         Die Art der Markierung als Coding.
     * @param zeitpunkt          Der Zeitpunkt der Markierung.
     * @param details            Optionale Details als Freitext zur Markierung.
     * @param gelesen            Gelesen-Status falls Markierung vom Typ 'gelesen' ist.
     * @param artDerArchivierung Details zur Art der Archivierung falls Markierung vom Typ 'archiviert' ist.
     * @return Das gespeicherte DocumentReference mit der neuen Markierung.
     */
    public DocumentReference applyFlagToDocument(
            DocumentReference document,
            Coding markierung,
            DateTimeType zeitpunkt,
            StringType details,
            BooleanType gelesen,
            Coding artDerArchivierung) {

        LOGGER.info("Applying flag '{}' to DocumentReference with ID: {}", markierung.getCode(), document.getIdElement().getIdPart());

        // Erstelle eine Kopie des Dokuments, um das Original nicht zu verändern (falls es von woanders referenziert wird)
        DocumentReference documentToUpdate = document.copy();

        // Aktualisiere die Meta-Informationen
        Meta meta = documentToUpdate.getMeta();
        if (meta == null) {
            meta = new Meta();
            documentToUpdate.setMeta(meta);
        }
        meta.setLastUpdated(new Date()); // Setze das aktuelle Datum als lastUpdated

        // Erstelle die Haupt-Extension für die Markierung
        Extension markierungExtension = new Extension("https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung");

        // Füge die Sub-Extensions hinzu
        markierungExtension.addExtension(new Extension("markierung", markierung));
        markierungExtension.addExtension(new Extension("zeitpunkt", zeitpunkt));

        if (details != null && !details.isEmpty()) {
            markierungExtension.addExtension(new Extension("details", details));
        }

        if ("gelesen".equals(markierung.getCode()) && gelesen != null) {
            markierungExtension.addExtension(new Extension("gelesen", gelesen));
        }

        if ("archiviert".equals(markierung.getCode()) && artDerArchivierung != null) {
            markierungExtension.addExtension(new Extension("artDerArchivierung", artDerArchivierung));
        }

        // Alte Markierungen entfernen, falls vorhanden und nur eine Markierung erlaubt ist (optional, je nach Anforderung)
        // meta.getExtension().removeIf(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung".equals(ext.getUrl()));
        
        // Füge die neue Markierungs-Extension zum Meta-Element hinzu
        meta.addExtension(markierungExtension);
        LOGGER.debug("Marking extension added to DocumentReference Meta: {}", meta.getExtension().size());

        // Speichere das aktualisierte Dokument
        LOGGER.debug("Updating DocumentReference in DAO...");
        DaoMethodOutcome outcome = daoRegistry.getResourceDao(DocumentReference.class).update(documentToUpdate);
        DocumentReference savedDocument = (DocumentReference) outcome.getResource();

        if (savedDocument != null) {
            LOGGER.info("DocumentReference ID: {} successfully updated and saved with new flag.", savedDocument.getIdElement().getIdPart());
        } else {
            LOGGER.error("Failed to save the updated DocumentReference. DAO update returned null.");
            // Hier könnte eine spezifischere Exception geworfen werden
            throw new RuntimeException("Fehler beim Speichern des aktualisierten DocumentReference");
        }
        return savedDocument;
    }
} 