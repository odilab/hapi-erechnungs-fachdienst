package ca.uhn.fhir.jpa.starter.custom.operation.processFlag;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.List;

// Imports für die kopierte Suchlogik
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.context.FhirContext;

@Service
public class ProcessFlagService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessFlagService.class);

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext; // Hinzufügen für Suchlogik

    @Autowired
    public ProcessFlagService(DaoRegistry daoRegistry, FhirContext fhirContext) { // FhirContext hinzufügen
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext; // Initialisieren
    }

    /**
     * Sucht ein Dokument anhand des Tokens (das als ID oder Identifier verwendet wird).
     * Diese Methode wurde aus DocumentRetrievalService kopiert, um Abhängigkeiten zu reduzieren.
     *
     * @param token Der Token (oft die logische ID oder ein Identifier-Wert) des Dokuments.
     * @return Das gefundene DocumentReference oder null.
     */
    public DocumentReference findDocumentByToken(String token) {
        LOGGER.info("Suche Dokument mit Token: {}", token);

        // VERSUCH 1: Direkter Lesezugriff über ID (wenn der Token die logische ID ist)
        try {
            IdType docId = new IdType("DocumentReference", token);
            DocumentReference document = daoRegistry.getResourceDao(DocumentReference.class).read(docId);
            if (document != null) {
                LOGGER.info("Dokument direkt via ID (Token) {} gefunden.", token);
                logDocumentDetails(document, token); // Log-Detail hinzugefügt
                return document;
            }
        } catch (ResourceNotFoundException e) {
            LOGGER.info("Dokument mit ID (Token) {} nicht gefunden (ResourceNotFoundException), versuche Identifier-Suche.", token);
        } catch (Exception e) {
            LOGGER.warn("Unerwarteter Fehler beim direkten Lesen der DocumentReference mit ID (Token) {}: {}. Versuche Identifier-Suche.", token, e.getMessage(), e);
        }

        // VERSUCH 2: Suche über Identifier mit spezifischem System
        DocumentReference document = findDocumentByIdentifierWithSystem(token, "https://gematik.de/fhir/sid/erg-token");
        if (document != null) {
            return document;
        }

        // VERSUCH 3: Suche über Identifier ohne Systemangabe
        document = findDocumentByIdentifierWithoutSystem(token);
        if (document != null) {
            return document;
        }

        // VERSUCH 4: Fallback - Durchsuche alle Dokumente und filtere manuell
        document = findDocumentByManualFiltering(token);

        if (document == null) {
            LOGGER.warn("Kein Dokument mit Token {} nach allen Suchstrategien gefunden.", token);
            // Hier keine Exception werfen, der Aufrufer (Provider) entscheidet, wie damit umzugehen ist.
        }

        return document;
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

    // --- Private Hilfsmethoden für findDocumentByToken (kopiert aus DocumentRetrievalService) ---

    private DocumentReference findDocumentByIdentifierWithSystem(String token, String system) {
        LOGGER.info("Versuche Suche via Identifier mit spezifischem System: {} und Wert: {}", system, token);
        SearchParameterMap paramMap = new SearchParameterMap();
        paramMap.add(DocumentReference.SP_IDENTIFIER, new TokenParam(system, token));

        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class).search(paramMap);

        if (results.size() > 0) {
            LOGGER.info("Dokument mit spezifischem Identifier-System und Token-Wert gefunden. Anzahl: {}", results.size());
            return (DocumentReference) results.getResources(0, 1).get(0);
        }

        LOGGER.info("Kein Dokument mit spezifischem Identifier-System ({}) und Wert {} gefunden.", system, token);
        return null;
    }

    private DocumentReference findDocumentByIdentifierWithoutSystem(String token) {
        LOGGER.info("Versuche Suche via Identifier ohne spezifisches System, nur mit Wert: {}", token);
        SearchParameterMap paramMap = new SearchParameterMap();
        paramMap.add(DocumentReference.SP_IDENTIFIER, new TokenParam(null, token));

        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class).search(paramMap);

        if (results.size() > 0) {
            LOGGER.info("Dokument mit Identifier (nur Wert) gefunden. Anzahl: {}", results.size());
            return (DocumentReference) results.getResources(0, 1).get(0);
        }

        LOGGER.info("Kein Dokument mit Identifier (nur Wert) {} gefunden.", token);
        return null;
    }

    private DocumentReference findDocumentByManualFiltering(String token) {
        LOGGER.warn("Letzter Versuch: Durchsuche ALLE DocumentReference-Ressourcen und filtere manuell nach dem Token '{}' im Identifier.", token);
        SearchParameterMap paramMap = new SearchParameterMap(); // Leere Map, um alle Ressourcen zu laden
        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class).search(paramMap);

        Integer resultSize = results.size();
        LOGGER.info("Gesamtzahl der DocumentReference-Ressourcen für manuelles Filtern: {}", resultSize != null ? resultSize : "(unbekannt/null)");

        if (resultSize != null && resultSize > 0) {
            List<IBaseResource> allDocs = results.getResources(0, resultSize);
            for (IBaseResource res : allDocs) {
                DocumentReference doc = (DocumentReference) res;
                for (Identifier identifier : doc.getIdentifier()) {
                    if (token.equals(identifier.getValue())) {
                        LOGGER.info("Dokument mit passendem Token-Wert '{}' im Identifier bei manueller Iteration gefunden. DocumentReference ID: {}", token, doc.getIdElement().toUnqualifiedVersionless().getValue());
                        return doc;
                    }
                }
            }
        }

        return null;
    }

    private void logDocumentDetails(DocumentReference document, String token) {
        // Loggen des Dokuments nur bei Bedarf und wenn Debugging aktiv ist
        if (LOGGER.isDebugEnabled()) {
            try {
                String documentAsJson = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(document);
                LOGGER.debug("Vollständiger Inhalt der via ID (Token) {} abgerufenen DocumentReference:\n{}", token, documentAsJson);
            } catch (Exception e) {
                LOGGER.debug("Fehler beim Serialisieren des Dokuments für Debug-Log: {}", e.getMessage());
            }
        }
    }
} 