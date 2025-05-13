package ca.uhn.fhir.jpa.starter.custom.operation.retrieve;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
import ca.uhn.fhir.jpa.starter.custom.operation.DocumentRetrievalService;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.DocumentReference.DocumentReferenceContentComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import org.hl7.fhir.r4.model.AuditEvent;

/**
 * Service für die Verarbeitung und Aufbereitung von Dokumenten
 */
@Service
public class DocumentProcessorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessorService.class);

    private final DaoRegistry daoRegistry;
    private final DocumentRetrievalService documentRetrievalService;
    private final AuditService auditService;

    // URLs für Extensions
    private static final String MARKIERUNG_MAIN_EXTENSION_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung";
    private static final String SUB_EXT_MARKIERUNG_URL = "markierung";
    private static final String SUB_EXT_GELESEN_URL = "gelesen";
    private static final String SUB_EXT_DETAILS_URL = "details";
    private static final String SUB_EXT_ZEITPUNKT_URL = "zeitpunkt";
    private static final String MARKIERUNG_CODING_SYSTEM_FOR_GELESEN = "https://gematik.de/fhir/erg/ValueSet/erg-dokument-artderarchivierung-vs";

    @Autowired
    public DocumentProcessorService(DaoRegistry daoRegistry, DocumentRetrievalService documentRetrievalService, AuditService auditService) {
        this.daoRegistry = daoRegistry;
        this.documentRetrievalService = documentRetrievalService;
        this.auditService = auditService;
    }

    /**
     * Verarbeitet das Dokument entsprechend der Parameter und erstellt die Parameters-Antwort.
     */
    public Parameters buildRetrieveResponse(
            DocumentReference originalDocRef,
            boolean retrieveAngereichertesPDF,
            boolean retrieveStrukturierteDaten,
            boolean retrieveOriginalPDF,
            boolean retrieveSignatur,
            AccessToken accessToken,
            RequestDetails requestDetails
    ) {
        Parameters responseParameters = new Parameters();
        LOGGER.info("buildRetrieveResponse - Beginn für DocumentReference ID: {}. Flags: angPDF={}, strDaten={}, origPDF={}, signatur={}",
            originalDocRef.getIdElement().toVersionless().getValue(), retrieveAngereichertesPDF, retrieveStrukturierteDaten, retrieveOriginalPDF, retrieveSignatur);

        // 1. Metadaten-DocumentReference hinzufügen
        DocumentReference metadataDocRef = addMetadataDocumentReference(originalDocRef, accessToken, responseParameters);

        // 2. Angereichertes PDF (erechnung)
        if (retrieveAngereichertesPDF) {
            addAngereichertesPDF(originalDocRef, responseParameters, requestDetails);
        }

        // 3. Strukturierte Daten (Invoice)
        if (retrieveStrukturierteDaten) {
            addStrukturierteDaten(originalDocRef, responseParameters, requestDetails);
        }

        // 4. Original PDF
        if (retrieveOriginalPDF) {
            addOriginalPDF(originalDocRef, responseParameters, requestDetails);
        }

        // 5. Signatur
        if (retrieveSignatur) {
            addSignatur(originalDocRef, responseParameters);
        }
        
        LOGGER.info("buildRetrieveResponse - Ende. {} Parameter erstellt.", responseParameters.getParameter().size());
        return responseParameters;
    }

    /**
     * Fügt die Metadaten des Dokuments (DocumentReference mit Markierung) zur Parameters-Antwort hinzu
     */
    private DocumentReference addMetadataDocumentReference(DocumentReference originalDocRef, AccessToken accessToken, Parameters responseParameters) {
        DocumentReference metadataDocRef = originalDocRef.copy();
        
        // Hinzufügen der Markierung-Extension
        addOrUpdateMarkierungExtension(metadataDocRef, accessToken);
        
        responseParameters.addParameter().setName("dokumentMetadaten").setResource(metadataDocRef);
        LOGGER.info("Metadaten-DocumentReference (mit ursprünglichem Content und Markierung) zum Parameter 'dokumentMetadaten' hinzugefügt.");
        
        return metadataDocRef;
    }

    /**
     * Fügt die Markierung-Extension zum DocumentReference hinzu oder aktualisiert sie
     */
    private void addOrUpdateMarkierungExtension(DocumentReference docRef, AccessToken accessToken) {
        // Hole oder erstelle die Haupt-Extension
        Extension markierungMainExtension = docRef.getExtensionsByUrl(MARKIERUNG_MAIN_EXTENSION_URL).stream().findFirst().orElse(null);
        if (markierungMainExtension == null) {
            markierungMainExtension = docRef.addExtension().setUrl(MARKIERUNG_MAIN_EXTENSION_URL);
        }

        // Sub-Extension "markierung" (Typ der Markierung)
        List<Extension> markierungTypSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_MARKIERUNG_URL);
        Extension markierungTypSubExtension = markierungTypSubExtensions.stream().findFirst().orElse(null);
        if (markierungTypSubExtension == null) {
            markierungTypSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_MARKIERUNG_URL);
        }
        
        // Gemäß Constraint: wenn "gelesen" (boolean) gesetzt wird, muss "markierung" (coding) den Code "gelesen" haben.
        Coding markierungCoding = new Coding()
            .setSystem(MARKIERUNG_CODING_SYSTEM_FOR_GELESEN)
            .setCode("gelesen")
            .setDisplay("Gelesen");
        markierungTypSubExtension.setValue(markierungCoding);

        // Sub-Extension "gelesen" (Boolean-Flag)
        List<Extension> gelesenSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_GELESEN_URL);
        Extension gelesenSubExtension = gelesenSubExtensions.stream().findFirst().orElse(null);
        if (gelesenSubExtension == null) {
            gelesenSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_GELESEN_URL);
        }
        gelesenSubExtension.setValue(new BooleanType(true));

        // Sub-Extension "details" (Wer hat die Aktion durchgeführt)
        List<Extension> detailsSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_DETAILS_URL);
        Extension detailsSubExtension = detailsSubExtensions.stream().findFirst().orElse(null);
        if (detailsSubExtension == null) {
            detailsSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_DETAILS_URL);
        }
        String detailsContent = createDetailsContent(accessToken);
        detailsSubExtension.setValue(new StringType(detailsContent));

        // Sub-Extension "zeitpunkt" (Wann wurde die Aktion durchgeführt)
        List<Extension> zeitpunktSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_ZEITPUNKT_URL);
        Extension zeitpunktSubExtension = zeitpunktSubExtensions.stream().findFirst().orElse(null);
        if (zeitpunktSubExtension == null) {
            zeitpunktSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_ZEITPUNKT_URL);
        }
        zeitpunktSubExtension.setValue(new DateTimeType(new java.util.Date()));
        
        LOGGER.info("Markierung-Extension zu metadataDocRef hinzugefügt/aktualisiert. Gelesen=true, Details='{}'", detailsContent);

        // Audit-Log für die Systemaktion des Markierens als gelesen
        try {
            Reference patientReferenceForAudit = null;
            if (docRef.getSubject() != null && docRef.getSubject().getReferenceElement().getResourceType().equals("Patient")) {
                patientReferenceForAudit = docRef.getSubject();
            }

            // Da dies eine Systemaktion ist, die durch den Lesezugriff des Nutzers ausgelöst wird,
            // ist der primäre Akteur des AuditEvents der Fachdienst.
            // Die Information über den auslösenden Nutzer (accessToken) ist im "detailsContent" der Extension enthalten.
            auditService.createSystemAuditEvent(
                AuditEvent.AuditEventAction.U, // U für Update (die DocumentReference wird mit der Extension aktualisiert)
                "update", // Korrekter Subtype-Code für die Systemaktion des Aktualisierens
                AuditEvent.AuditEventOutcome._0, // Erfolg
                new Reference(docRef.getIdElement().toVersionless()),
                "DocumentReference Gelesen-Markierung", // Resource Name
                docRef.getIdElement().toVersionless().getValue(), // entityWhatDisplay
                "System hat DocumentReference ID '" + docRef.getIdElement().getIdPart() + "' als gelesen markiert (Extension). Ausgelöst durch Nutzer: " + detailsContent,
                patientReferenceForAudit // patientReference für Versicherter-Slice, falls vorhanden
            );
        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen des System-AuditEvents für Markierung-Extension in DocumentProcessorService: {}", e.getMessage(), e);
            // Die Hauptoperation sollte hierdurch nicht fehlschlagen
        }
    }

    /**
     * Erstellt den Inhalt für die Details-Extension
     */
    private String createDetailsContent(AccessToken accessToken) {
        Profession profession = accessToken.getProfession();
        String idNum = accessToken.getIdNumber();

        if (profession == Profession.VERSICHERTER) {
            return "Gelesen-Markierung gesetzt von: Versicherter (ID: " + idNum + ", KVNR: " + accessToken.getKvnr().orElse("nicht vorhanden") + ")";
        } else if (profession == Profession.KOSTENTRAEGER) {
            return "Gelesen-Markierung gesetzt von: Kostenträger (ID: " + idNum + ", TelematikID: " + accessToken.getTelematikId().orElse("nicht vorhanden") + ")";
        } else if (profession != null) {
            return "Gelesen-Markierung gesetzt von: " + profession.toString() + " (ID: " + idNum + ")";
        } else {
            return "Gelesen-Markierung gesetzt von: Unbekannte Profession (ID: " + idNum + ")";
        }
    }

    /**
     * Fügt das angereicherte PDF zur Parameters-Antwort hinzu
     */
    private void addAngereichertesPDF(DocumentReference originalDocRef, Parameters responseParameters, RequestDetails requestDetails) {
        originalDocRef.getContent().stream()
            .filter(this::isAngereichertesPDF)
            .findFirst()
            .ifPresent(content -> {
                try {
                    Binary pdfBinary = documentRetrievalService.loadBinaryFromUrl(content.getAttachment().getUrl(), requestDetails);
                    if (pdfBinary != null) {
                        responseParameters.addParameter().setName("angereichertesPDF").setResource(pdfBinary);
                        LOGGER.info("Angereichertes PDF (erechnung) als Binary-Ressource zum Parameter 'angereichertesPDF' hinzugefügt. URL: {}", content.getAttachment().getUrl());
                    }
                } catch (Exception e) {
                    LOGGER.warn("Konnte angereichertes PDF (erechnung) nicht laden von URL {}: {}", content.getAttachment().getUrl(), e.getMessage());
                }
            });
    }

    /**
     * Fügt die strukturierten Daten (Invoice) zur Parameters-Antwort hinzu
     */
    private void addStrukturierteDaten(DocumentReference originalDocRef, Parameters responseParameters, RequestDetails requestDetails) {
        originalDocRef.getContent().stream()
            .filter(this::isStrukturierteDaten)
            .findFirst()
            .ifPresent(content -> {
                try {
                    IdType invoiceId = new IdType(content.getAttachment().getUrl());
                    if ("Invoice".equals(invoiceId.getResourceType()) && invoiceId.hasIdPart()) {
                         Invoice invoice = daoRegistry.getResourceDao(Invoice.class).read(invoiceId, requestDetails);
                         if (invoice != null) {
                            responseParameters.addParameter().setName("strukturierteDaten").setResource(invoice);
                            LOGGER.info("Strukturierte Daten (Invoice) zum Parameter 'strukturierteDaten' hinzugefügt. ID: {}", invoiceId.getValue());
                         } else {
                             LOGGER.warn("Invoice mit ID {} nicht gefunden (null zurückgegeben).", invoiceId.getValue());
                         }
                    } else {
                         LOGGER.warn("URL {} für strukturierte Daten verweist nicht auf eine gültige Invoice-Ressource oder hat keinen ID-Teil.", content.getAttachment().getUrl());
                    }
                } catch (ResourceNotFoundException e) {
                    LOGGER.warn("Invoice Ressource nicht gefunden unter URL {}: {}", content.getAttachment().getUrl(), e.getMessage());
                } catch (Exception e) {
                    LOGGER.warn("Konnte strukturierte Daten (Invoice) nicht laden von URL {}: {}", content.getAttachment().getUrl(), e.getMessage(), e);
                }
            });
    }

    /**
     * Fügt das Original-PDF zur Parameters-Antwort hinzu
     */
    private void addOriginalPDF(DocumentReference originalDocRef, Parameters responseParameters, RequestDetails requestDetails) {
        // Debug-Ausgaben zu relatesTo
        logRelatesTo(originalDocRef);

        originalDocRef.getRelatesTo().stream()
            .filter(rel -> rel.hasCode() && "transforms".equals(rel.getCode().toCode()) && rel.hasTarget() && rel.getTarget().hasReference())
            .findFirst()
            .ifPresent(relatesToEntry -> {
                String sourceDocRefUrl = relatesToEntry.getTarget().getReference();
                LOGGER.info("OriginalPDF: Versuche Original-DocumentReference über relatesTo-Referenz zu laden. URL: {}", sourceDocRefUrl);
                try {
                    IdType originalDocRefId = new IdType(sourceDocRefUrl);
                    if (!"DocumentReference".equals(originalDocRefId.getResourceType()) || !originalDocRefId.hasIdPart()){
                        LOGGER.warn("OriginalPDF: Referenz in relatesTo ({}) ist keine gültige DocumentReference ID.", sourceDocRefUrl);
                        return;
                    }
                    DocumentReference sourceDocRef = daoRegistry.getResourceDao(DocumentReference.class).read(originalDocRefId, requestDetails);
                    if (sourceDocRef != null) {
                        processOriginalDocumentReference(sourceDocRef, responseParameters, requestDetails);
                    } else {
                        LOGGER.warn("OriginalPDF: Original-DocumentReference via relatesTo ({}) nicht gefunden (null zurückgegeben).", sourceDocRefUrl);
                    }
                } catch (ResourceNotFoundException e) {
                    LOGGER.warn("OriginalPDF: Original-DocumentReference Ressource nicht gefunden unter URL {}: {}", sourceDocRefUrl, e.getMessage());
                } catch (Exception e) {
                    LOGGER.warn("OriginalPDF: Konnte referenziertes Original-DocumentReference nicht laden von {}: {}", sourceDocRefUrl, e.getMessage(), e);
                }
            });
    }

    /**
     * Verarbeitet die Original-DocumentReference, um das Original-PDF zu extrahieren
     */
    private void processOriginalDocumentReference(DocumentReference sourceDocRef, Parameters responseParameters, RequestDetails requestDetails) {
        sourceDocRef.getContent().stream()
            .filter(c -> c.hasAttachment() && "application/pdf".equals(c.getAttachment().getContentType()))
            .findFirst()
            .ifPresent(pdfContent -> {
                try {
                    Binary originalPdfBinary = null;
                    if (pdfContent.getAttachment().hasData()) {
                        originalPdfBinary = new Binary();
                        originalPdfBinary.setContentType(pdfContent.getAttachment().getContentType());
                        originalPdfBinary.setData(pdfContent.getAttachment().getData());
                        LOGGER.info("OriginalPDF: Original PDF direkt aus Attachment.data der sourceDocRef erstellt.");
                    } else if (pdfContent.getAttachment().hasUrl()) {
                        originalPdfBinary = documentRetrievalService.loadBinaryFromUrl(pdfContent.getAttachment().getUrl(), requestDetails);
                    }

                    if (originalPdfBinary != null) {
                        responseParameters.addParameter().setName("originalPDF").setResource(originalPdfBinary);
                        LOGGER.info("OriginalPDF: Original PDF als Binary-Ressource zum Parameter 'originalPDF' hinzugefügt.");
                    } else {
                        LOGGER.warn("OriginalPDF: Konnte keine Daten oder URL für das Original PDF im sourceDocRef.content finden.");
                    }
                } catch (Exception e) {
                    LOGGER.warn("OriginalPDF: Fehler beim Verarbeiten/Extrahieren des Original PDF Inhalts: {}", e.getMessage(), e);
                }
            });
    }

    /**
     * Fügt die Signatur zur Parameters-Antwort hinzu
     */
    private void addSignatur(DocumentReference originalDocRef, Parameters responseParameters) {
        originalDocRef.getExtension().stream()
            .filter(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-docref-signature".equals(ext.getUrl()))
            .findFirst()
            .ifPresent(ext -> {
                if (ext.getValue() instanceof Signature) {
                    Signature signature = (Signature) ext.getValue();
                    responseParameters.addParameter().setName("signatur").setValue(signature);
                    LOGGER.info("Signatur aus Extension zum Parameter 'signatur' hinzugefügt.");
                } else {
                    LOGGER.warn("Signatur-Extension gefunden, aber der Wert ist nicht vom Typ Signature. Gefunden: {}", ext.getValue().fhirType());
                }
            });
    }

    // Hilfsmethoden

    private boolean isAngereichertesPDF(DocumentReferenceContentComponent content) {
        return content.hasAttachment() && 
               "application/pdf".equals(content.getAttachment().getContentType()) &&
               content.hasFormat() && 
               "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(content.getFormat().getSystem()) &&
               "erechnung".equals(content.getFormat().getCode()) && 
               content.getAttachment().hasUrl();
    }

    private boolean isStrukturierteDaten(DocumentReferenceContentComponent content) {
        return content.hasAttachment() && 
               "application/fhir+json".equals(content.getAttachment().getContentType()) &&
               content.hasFormat() && 
               "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(content.getFormat().getSystem()) &&
               "rechnungsinhalt".equals(content.getFormat().getCode()) && 
               content.getAttachment().hasUrl();
    }

    private void logRelatesTo(DocumentReference docRef) {
        if (docRef.hasRelatesTo()) {
            LOGGER.info("OriginalPDF: DEBUG - originalDocRef.getRelatesTo() enthält {} Elemente.", docRef.getRelatesTo().size());
            for (int i = 0; i < docRef.getRelatesTo().size(); i++) {
                DocumentReference.DocumentReferenceRelatesToComponent rel = docRef.getRelatesTo().get(i);
                String relCode = rel.hasCode() ? rel.getCode().toCode() : "null";
                String targetRef = rel.hasTarget() && rel.getTarget().hasReference() ? rel.getTarget().getReference() : "null";
                LOGGER.info("OriginalPDF: DEBUG - relatesTo[{}]: code='{}', target.reference='{}'", i, relCode, targetRef);
            }
        } else {
            LOGGER.info("OriginalPDF: DEBUG - originalDocRef hat keine relatesTo-Einträge.");
        }
    }
} 