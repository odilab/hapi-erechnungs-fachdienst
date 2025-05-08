package ca.uhn.fhir.jpa.starter.custom.operation.submit.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.operation.submit.TokenGenerationService;
import ca.uhn.fhir.jpa.starter.custom.operation.submit.PdfEnrichmentService;
import ca.uhn.fhir.jpa.starter.custom.signature.FhirSignatureService;
import ca.uhn.fhir.jpa.starter.custom.signature.KeyLoader;
import ca.uhn.fhir.jpa.starter.custom.signature.SignatureService;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Invoice;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.hl7.fhir.r4.model.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import java.io.IOException;

@Component
public class RechnungValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RechnungValidator.class);
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    private final CustomValidator customValidator;
    private final DaoRegistry daoRegistry;
    private final TokenGenerationService tokenGenerationService;
    private final PdfEnrichmentService pdfEnrichmentService;
    private final FhirContext ctx;
    private final SignatureService signatureService;
    private final FhirSignatureService fhirSignatureService;

    /**
     * Container-Klasse für das Ergebnis der Validierung und Transformation.
     */
    public static class ValidationAndTransformResult {
        public final OperationOutcome warnings;
        public final DocumentReference transformedRechnung;
        public final List<ProcessedAttachmentResult> processedAttachments;

        ValidationAndTransformResult(OperationOutcome warnings, DocumentReference transformedRechnung, List<ProcessedAttachmentResult> processedAttachments) {
            this.warnings = warnings;
            this.transformedRechnung = transformedRechnung;
            this.processedAttachments = processedAttachments;
        }
    }

    /**
     * Container-Klasse für das Ergebnis der Anhangverarbeitung.
     */
    public static class ProcessedAttachmentResult {
        public final DocumentReference savedAttachmentDocumentReference;
        public final OperationOutcome attachmentProcessingWarnings;

        ProcessedAttachmentResult(DocumentReference savedAttachmentDocumentReference, OperationOutcome attachmentProcessingWarnings) {
            this.savedAttachmentDocumentReference = savedAttachmentDocumentReference;
            this.attachmentProcessingWarnings = attachmentProcessingWarnings;
        }
    }

    @Autowired
    public RechnungValidator(CustomValidator customValidator, DaoRegistry daoRegistry, TokenGenerationService tokenGenerationService, PdfEnrichmentService pdfEnrichmentService, FhirContext ctx, SignatureService signatureService, FhirSignatureService fhirSignatureService) {
        this.customValidator = customValidator;
        this.daoRegistry = daoRegistry;
        this.tokenGenerationService = tokenGenerationService;
        this.pdfEnrichmentService = pdfEnrichmentService;
        this.ctx = ctx;
        this.signatureService = signatureService;
        this.fhirSignatureService = fhirSignatureService;
    }

    /**
     * Validiert die übergebene DocumentReference (Rechnung), extrahiert, validiert und speichert ggf. enthaltene Invoices,
     * speichert die Rechnung und eine transformierte Version (mit Links zur Invoice) im Normal-Modus.
     * - Führt immer die FHIR-Validierung für DocumentReference und extrahierte Invoices durch.
     * - Wenn modus='normal', werden DocumentReference und Invoices gespeichert und die transformierte DocumentReference angepasst.
     * - Wenn modus='test', wird nur validiert.
     * Wirft eine UnprocessableEntityException, wenn die FHIR-Validierung FATAL oder ERROR ergibt (für DocRef oder Invoice).
     *
     * @param rechnung Die zu validierende DocumentReference.
     * @param modus Der Verarbeitungsmodus ('normal' oder 'test').
     * @param accessToken Das AccessToken mit den Informationen des authentifizierten Benutzers.
     * @param anhaenge Eine Liste von DocumentReferences, die als Anhänge zur Hauptrechnung dienen.
     * @return Ein ValidationAndTransformResult Objekt, das ggf. Warnungen und die transformierte Rechnung enthält.
     */
    public ValidationAndTransformResult validate(DocumentReference rechnung, CodeType modus, AccessToken accessToken, List<DocumentReference> anhaenge) {
        DocumentReference finalTransformedRechnung = null;
        List<SingleValidationMessage> allWarningsAndInfos = new ArrayList<>();
        Map<Integer, String> invoiceUrlMap = new HashMap<>();
        Map<Integer, byte[]> pdfDataToEnrichMap = new HashMap<>();
        byte[] pdfDataForSigning = null;
        byte[] invoiceJsonDataForSigning = null;
        String patientReferenceFromInvoice = null;
        List<ProcessedAttachmentResult> processedAttachmentResults = new ArrayList<>();

        if (rechnung == null) {
            LOGGER.warn("validate wurde mit einer null DocumentReference aufgerufen.");
            return new ValidationAndTransformResult(null, null, processedAttachmentResults);
        }

        String modusValue = (modus != null) ? modus.getValueAsString() : "normal";

        LOGGER.debug("Starte Validierung/Verarbeitung für DocumentReference (Rechnung) mit ID: {}. Modus: {}",
                rechnung.hasId() ? rechnung.getIdElement().getValue() : "keine ID", modusValue);

        // 1. FHIR-validieren der Haupt-DocumentReference
        ValidationResult docRefValidationResult = customValidator.validateAndReturnResult(rechnung);
        handleValidationResult(docRefValidationResult, allWarningsAndInfos, "DocumentReference");

        // 2. Extrahiere, parse, validiere (und speichere ggf.) Invoices und sammle PDFs
        if (rechnung.hasContent()) {
            for (int i = 0; i < rechnung.getContent().size(); i++) {
                DocumentReference.DocumentReferenceContentComponent content = rechnung.getContent().get(i);
                if (content.hasAttachment() && content.getAttachment().hasContentType() && content.getAttachment().hasData()) {
                    Attachment attachment = content.getAttachment();
                    String contentType = attachment.getContentType();
                    boolean isFhirJson = "application/fhir+json".equalsIgnoreCase(contentType);
                    boolean isFhirXml = "application/fhir+xml".equalsIgnoreCase(contentType);
                    boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);

                    if (isFhirJson || isFhirXml) {
                        LOGGER.debug("Found potential FHIR Invoice in content index {} with contentType: {}", i, contentType);
                        try {
                            // Größenprüfung vor der Dekodierung
                            if (attachment.getData().length > MAX_ATTACHMENT_SIZE_BYTES) {
                                LOGGER.error("Attachment-Daten bei Index {} überschreiten die maximale Größe von {} Bytes.", i, MAX_ATTACHMENT_SIZE_BYTES);
                                throw new UnprocessableEntityException("Attachment bei Index " + i + " überschreitet die maximale Größe von 10MB.");
                            }
                            byte[] decodedBytes = Base64.getDecoder().decode(attachment.getData());
                            String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);
                            
                            IParser parser = isFhirJson ? ctx.newJsonParser() : ctx.newXmlParser();
                            IBaseResource parsedResource = parser.parseResource(decodedString);

                            if (parsedResource instanceof Invoice) {
                                Invoice parsedInvoice = (Invoice) parsedResource;
                                LOGGER.info("Successfully parsed Invoice from content index {}", i);

                                // Store for signing if it's the first one
                                if (invoiceJsonDataForSigning == null) {
                                    invoiceJsonDataForSigning = decodedBytes; // Store raw JSON/XML bytes
                                    LOGGER.debug("FHIR Invoice-Daten (Index {}) für Signatur zwischengespeichert.", i);
                                }

                                // Extract patient reference from Invoice for context.related
                                if (patientReferenceFromInvoice == null && parsedInvoice.hasSubject() && parsedInvoice.getSubject().hasReference()) {
                                    patientReferenceFromInvoice = parsedInvoice.getSubject().getReference();
                                    LOGGER.debug("Patientenreferenz '{}' aus Invoice (Index {}) für context.related extrahiert.", patientReferenceFromInvoice, i);
                                }

                                // Validiere die Invoice
                                ValidationResult invoiceValidationResult = customValidator.validateAndReturnResult(parsedInvoice);
                                handleValidationResult(invoiceValidationResult, allWarningsAndInfos, "Invoice (Index " + i + ")");

                                // Speichere Invoice nur im Normal-Modus (nach erfolgreicher Validierung)
                                if ("normal".equalsIgnoreCase(modusValue)) {
                                    try {
                                        LOGGER.info("Modus 'normal': Speichere Invoice aus Content-Index {} in der Datenbank.", i);
                                        DaoMethodOutcome savedInvoiceOutcome = daoRegistry.getResourceDao(Invoice.class).create(parsedInvoice);
                                        if (savedInvoiceOutcome.getCreated() != null && savedInvoiceOutcome.getCreated()) {
                                            String invoiceUrl = savedInvoiceOutcome.getId().toUnqualifiedVersionless().getValue();
                                            LOGGER.info("Invoice erfolgreich gespeichert mit ID/URL: {}", invoiceUrl);
                                            invoiceUrlMap.put(i, invoiceUrl); // Index und URL merken
                                        } else {
                                            LOGGER.error("Speichern der Invoice aus Index {} hat kein 'created=true' zurückgegeben. Outcome: {}", i, savedInvoiceOutcome);
                                            throw new InternalErrorException("Konnte die extrahierte Invoice nicht speichern.");
                                        }
                                    } catch (Exception eSave) {
                                        LOGGER.error("Fehler beim Speichern der Invoice aus Content-Index {}.", i, eSave);
                                        throw new InternalErrorException("Fehler beim Speichern der extrahierten Invoice: " + eSave.getMessage(), eSave);
                                    }
                                }
                            } else {
                                LOGGER.warn("Geparste Ressource aus Content-Index {} ist keine Invoice, sondern {}. Wird ignoriert.", i, parsedResource.fhirType());
                                // Optional: Warnung zum OperationOutcome hinzufügen?
                            }
                        } catch (IllegalArgumentException eDecode) {
                            LOGGER.error("Fehler beim Base64-Dekodieren der Daten aus Content-Index {}.", i, eDecode);
                            // Wirf Fehler oder füge Warnung hinzu?
                            throw new UnprocessableEntityException("Ungültige Base64-Kodierung in Attachment bei Index " + i + ": " + eDecode.getMessage());
                        } catch (Exception eParse) {
                            LOGGER.error("Fehler beim Parsen der FHIR-Ressource aus Content-Index {}. ContentType: {}.", i, contentType, eParse);
                            throw new UnprocessableEntityException("FHIR-Parsing-Fehler in Attachment bei Index " + i + ": " + eParse.getMessage());
                        }
                    } else if (isPdf) {
                        LOGGER.debug("Found PDF Attachment in content index {}", i);
                        byte[] pdfData = attachment.getData();
                        // Größenprüfung für PDF
                        if (pdfData.length > MAX_ATTACHMENT_SIZE_BYTES) {
                            LOGGER.error("PDF-Attachment-Daten bei Index {} überschreiten die maximale Größe von {} Bytes.", i, MAX_ATTACHMENT_SIZE_BYTES);
                            throw new UnprocessableEntityException("PDF-Attachment bei Index " + i + " überschreitet die maximale Größe von 10MB.");
                        }
                        try (PDDocument ignored = PDDocument.load(pdfData)) {
                            // Erfolgreich geladen, also wahrscheinlich eine valide PDF
                            LOGGER.debug("PDF in content index {} scheint valide zu sein.", i);
                             // Store for signing if it's the first one
                            if (pdfDataForSigning == null) {
                                pdfDataForSigning = pdfData; // Store raw PDF bytes
                                LOGGER.debug("PDF-Daten (Index {}) für Signatur zwischengespeichert.", i);
                            }
                            if ("normal".equalsIgnoreCase(modusValue)) {
                                pdfDataToEnrichMap.put(i, pdfData); // Für spätere Anreicherung merken
                            }
                        } catch (IOException ePdfLoad) {
                            LOGGER.error("Fehler beim Laden/Validieren der PDF aus Content-Index {}. Ist es eine valide PDF?", i, ePdfLoad);
                            throw new UnprocessableEntityException("Anhang bei Index " + i + " ist keine valide PDF: " + ePdfLoad.getMessage());
                        }
                    }
                }
            }
        }

        // 3. Speichern und Transformieren der DocumentReference nur im 'normal'-Modus 
        if ("normal".equalsIgnoreCase(modusValue)) {
            try {
                LOGGER.info("Modus 'normal': Speichere initiale DocumentReference (Rechnung) mit ID: {} in der Datenbank.",
                        rechnung.hasId() ? rechnung.getIdElement().getValue() : "(wird generiert)");
                DaoMethodOutcome docRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).create(rechnung);
                if (docRefOutcome.getCreated() != null && docRefOutcome.getCreated()) {
                    String originalDocRefId = docRefOutcome.getId().getValue();
                    LOGGER.info("Initiale DocumentReference erfolgreich gespeichert mit ID: {}", originalDocRefId);
                    DocumentReference savedRechnung = (DocumentReference) docRefOutcome.getResource();
                      if (savedRechnung == null) {
                        LOGGER.error("Konnte die gespeicherte initiale DocumentReference nicht vom DaoMethodOutcome abrufen.");
                        throw new InternalErrorException("Fehler nach dem Speichern der Rechnung: Gespeicherte Ressource nicht verfügbar.");
                    }

                    // Erstelle Kopie für die Transformation
                    DocumentReference transformedRechnung = savedRechnung.copy();
                    transformedRechnung.setId((String)null); 

                    // Setze/Überschreibe Metadaten der transformierten Rechnung
                    Meta meta = transformedRechnung.getMeta();
                    if (meta == null) {
                        meta = new Meta();
                        transformedRechnung.setMeta(meta);
                    }
                    // Vorhandene relevante Metadaten entfernen
                    meta.getExtension().removeIf(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung".equals(ext.getUrl()));
                    meta.getProfile().clear(); // Alle Profile entfernen, um Duplikate zu vermeiden
                    meta.getTag().removeIf(tag -> "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs".equals(tag.getSystem()));

                    // 1. Markierungserweiterung hinzufügen
                    Extension markierungOuterExt = new Extension("https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung");
                    Extension markierungInnerExt = new Extension("markierung");
                    Coding markierungCoding = new Coding()
                        .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-dokument-artderarchivierung-cs")
                        .setCode("persoenlich")
                        .setDisplay("Persönliche Ablage");
                    markierungInnerExt.setValue(markierungCoding);
                    markierungOuterExt.addExtension(markierungInnerExt);
                    meta.addExtension(markierungOuterExt);

                    // 2. Profil hinzufügen
                    meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-dokumentenmetadaten|1.1.0-RC1");

                    // 3. Rechnungsstatus-Tag hinzufügen
                    meta.addTag(
                        "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs",
                        "offen",
                        "Offen"
                    );
                    LOGGER.info("Metadaten (Markierung, Profil, Status-Tag) für transformierte Rechnung gesetzt/überschrieben.");

                    // Setze DocumentReference.context.related basierend auf der extrahierten Patientenreferenz
                    if (patientReferenceFromInvoice != null) {
                        DocumentReference.DocumentReferenceContextComponent context = transformedRechnung.getContext();
                        if (!transformedRechnung.hasContext()) { // Prüfen, ob Kontext bereits existiert, sonst neu erstellen
                            context = new DocumentReference.DocumentReferenceContextComponent();
                            transformedRechnung.setContext(context);
                        }
                        context.getRelated().clear(); // Vorhandene Einträge entfernen

                        Reference relatedPatientRef = new Reference(patientReferenceFromInvoice);
                        relatedPatientRef.setType("Patient");
                        // Hinweis: Für das Setzen von relatedPatientRef.setDisplay() müsste die Patient-Ressource
                        // zusätzlich vom Server geladen werden, um den Namen zu extrahieren.
                        // z.B. Patient p = daoRegistry.getResourceDao(Patient.class).read(new IdType(patientReferenceFromInvoice));
                        // if (p != null && p.hasName()) { relatedPatientRef.setDisplay(p.getNameFirstRep().getNameAsSingleString()); }
                        context.addRelated(relatedPatientRef);
                        LOGGER.info("DocumentReference.context.related für transformierte Rechnung mit Patientenreferenz '{}' gesetzt.", patientReferenceFromInvoice);
                    } else {
                        LOGGER.warn("Keine Patientenreferenz aus einer Invoice gefunden. DocumentReference.context.related wird nicht gesetzt.");
                    }

                    // --> Modifiziere die transformierte Rechnung basierend auf gespeicherten Invoices
                    if (!invoiceUrlMap.isEmpty()) {
                        LOGGER.debug("Modifiziere transformierte Rechnung: Ersetze Daten durch URLs für Indizes: {}", invoiceUrlMap.keySet());
                        for (Map.Entry<Integer, String> entry : invoiceUrlMap.entrySet()) {
                            int contentIndex = entry.getKey();
                            String invoiceUrl = entry.getValue();
                            if (contentIndex < transformedRechnung.getContent().size()) {
                                Attachment attachmentToModify = transformedRechnung.getContent().get(contentIndex).getAttachment();
                                attachmentToModify.setData(null); // Daten entfernen
                                attachmentToModify.setUrl(invoiceUrl); // URL setzen
                                LOGGER.debug("Content-Index {} in transformierter Rechnung: data entfernt, url '{}' gesetzt.", contentIndex, invoiceUrl);
                            } else {
                                LOGGER.error("Interner Fehler: Content-Index {} aus invoiceUrlMap ist außerhalb der Grenzen der transformierten Rechnung (size {}). Überspringe.", contentIndex, transformedRechnung.getContent().size());
                            }
                        }
                    }
                    
                    // Füge relatesTo hinzu
                    DocumentReference.DocumentReferenceRelatesToComponent relatesTo = new DocumentReference.DocumentReferenceRelatesToComponent();
                    relatesTo.setCode(DocumentReference.DocumentRelationshipType.TRANSFORMS);
                    relatesTo.setTarget(new Reference(originalDocRefId));
                    transformedRechnung.addRelatesTo(relatesTo);

                    // Generiere eindeutige ID für die transformierte Rechnung
                    String generatedTokenId = tokenGenerationService.generateUniqueToken();
                    transformedRechnung.setId(generatedTokenId);
                    LOGGER.info("Generierte eindeutige ID für transformierte Rechnung (und PDF QR-Codes): {}", generatedTokenId);

                    // Setze/Überschreibe DocumentReference.author mit Telematik-ID aus AccessToken
                    if (accessToken != null && accessToken.getTelematikId().isPresent()) {
                        String telematikId = accessToken.getTelematikId().get();
                        transformedRechnung.getAuthor().clear(); // Vorhandene Autoren entfernen
                        Reference authorReference = new Reference();
                        Identifier authorIdentifier = new Identifier()
                            .setSystem("https://gematik.de/fhir/sid/telematik-id") // Standard-System für Telematik-ID
                            .setValue(telematikId);
                        authorReference.setIdentifier(authorIdentifier);
                        // Optional: Display-Name setzen, falls gewünscht/verfügbar
                        // authorReference.setDisplay("Anzeiger Name des Authors");
                        transformedRechnung.addAuthor(authorReference);
                        LOGGER.info("DocumentReference.author der transformierten Rechnung mit Telematik-ID {} gesetzt.", telematikId);
                    } else {
                        LOGGER.warn("Keine Telematik-ID im AccessToken gefunden oder AccessToken ist null. DocumentReference.author wird nicht gesetzt/überschrieben.");
                    }

                    // PDFs anreichern, als Binary speichern und URLs in transformedRechnung eintragen
                    Map<Integer, String> storedPdfBinaryUrlMap = new HashMap<>();
                    if (!pdfDataToEnrichMap.isEmpty()) {
                        LOGGER.debug("Verarbeite {} PDFs für Anreicherung und Speicherung.", pdfDataToEnrichMap.size());
                        for (Map.Entry<Integer, byte[]> pdfEntry : pdfDataToEnrichMap.entrySet()) {
                            int pdfContentIndex = pdfEntry.getKey();
                            byte[] rawPdfData = pdfEntry.getValue();
                            try {
                                LOGGER.info("Reichere PDF aus Content-Index {} mit Token {} an und speichere als Binary.", pdfContentIndex, generatedTokenId);
                                // savedRechnung wird für PdfEnrichmentService.extractStructuredData verwendet, falls es eine Invoice einbetten soll
                                Binary enrichedPdfBinary = pdfEnrichmentService.enrichPdfWithBarcodeAndAttachment(rawPdfData, generatedTokenId, savedRechnung);
                                
                                DaoMethodOutcome savedBinaryOutcome = daoRegistry.getResourceDao(Binary.class).create(enrichedPdfBinary);
                                if (savedBinaryOutcome.getCreated() != null && savedBinaryOutcome.getCreated()) {
                                    String pdfBinaryUrl = savedBinaryOutcome.getId().toUnqualifiedVersionless().getValue();
                                    LOGGER.info("Angereicherte PDF (aus Index {}) als Binary gespeichert mit URL: {}", pdfContentIndex, pdfBinaryUrl);
                                    storedPdfBinaryUrlMap.put(pdfContentIndex, pdfBinaryUrl);
                                } else {
                                    LOGGER.error("Speichern der angereicherten PDF (aus Index {}) als Binary schlug fehl.", pdfContentIndex);
                                    throw new InternalErrorException("Konnte angereicherte PDF nicht als Binary speichern (Index " + pdfContentIndex + ").");
                                }
                            } catch (Exception eEnrichOrSave) {
                                LOGGER.error("Fehler bei PDF-Anreicherung/Speicherung für Index {}.", pdfContentIndex, eEnrichOrSave);
                                throw new InternalErrorException("Fehler bei PDF-Verarbeitung (Index " + pdfContentIndex + "): " + eEnrichOrSave.getMessage(), eEnrichOrSave);
                            }
                        }
                    }

                    // URLs der gespeicherten, angereicherten PDFs in transformedRechnung eintragen
                    if (!storedPdfBinaryUrlMap.isEmpty()) {
                        LOGGER.debug("Aktualisiere transformierte Rechnung mit URLs der gespeicherten PDFs für Indizes: {}", storedPdfBinaryUrlMap.keySet());
                        for (Map.Entry<Integer, String> entry : storedPdfBinaryUrlMap.entrySet()) {
                            int contentIndex = entry.getKey();
                            String pdfUrl = entry.getValue();
                            if (contentIndex < transformedRechnung.getContent().size()) {
                                Attachment attachmentToModify = transformedRechnung.getContent().get(contentIndex).getAttachment();
                                attachmentToModify.setData(null); 
                                attachmentToModify.setUrl(pdfUrl); 
                                LOGGER.debug("Content-Index {} in transformierter Rechnung (PDF): data entfernt, url '{}' gesetzt.", contentIndex, pdfUrl);
                            } else {
                                LOGGER.error("Interner Fehler: PDF Content-Index {} ist außerhalb der Grenzen.", contentIndex);
                            }
                        }
                    }

                    // ---------- NEUE POSITION für Anhangverarbeitung ----------
                    if (anhaenge != null && !anhaenge.isEmpty()) {
                        LOGGER.info("Starte Verarbeitung für {} Anhänge im Modus '{}'.", anhaenge.size(), modusValue);
                        for (int i = 0; i < anhaenge.size(); i++) {
                            DocumentReference anhangDocRef = anhaenge.get(i);
                            String anhangIdLog = anhangDocRef.hasId() ? anhangDocRef.getIdElement().getValue() : "Anhang #" + (i+1) + " (ohne ID)";
                            List<SingleValidationMessage> attachmentSpecificWarnings = new ArrayList<>();
                            DocumentReference savedAnhangDocRef = null;
            
                            try {
                                LOGGER.debug("Validiere Anhang: {}", anhangIdLog);
                                ValidationResult anhangValidationResult = customValidator.validateAndReturnResult(anhangDocRef);
                                handleValidationResult(anhangValidationResult, attachmentSpecificWarnings, "Anhang " + anhangIdLog);
            
                                // Kopie für Modifikationen erstellen
                                DocumentReference anhangToProcess = anhangDocRef.copy();
            
                                // Attachments im Anhang als Binary speichern und URL ersetzen
                                if (anhangToProcess.hasContent()) {
                                    for (DocumentReference.DocumentReferenceContentComponent content : anhangToProcess.getContent()) {
                                        if (content.hasAttachment() && content.getAttachment().hasData()) {
                                            Attachment attachment = content.getAttachment();
                                            byte[] attachmentData = attachment.getData(); // Annahme: data ist byte[]
                                            String attachmentContentType = attachment.hasContentType() ? attachment.getContentType() : "application/octet-stream";

                                            // Daten als Base64 interpretieren, wenn es ein String ist (konsistent mit Hauptrechnungs-PDF/Invoice Handling)
                                            // Dies ist eine Annahme, ggf. anpassen, wenn .data immer byte[] ist.
                                            if (attachment.getDataElement() instanceof org.hl7.fhir.r4.model.Base64BinaryType && attachment.getDataElement().hasValue()) {
                                                 // Bereits byte[], tun nichts extra. Wenn es ein StringType wäre, müssten wir Base64.getDecoder().decode verwenden.
                                                 // Für den Moment gehen wir davon aus, dass wenn .getData() genutzt wird, es byte[] ist.
                                                 // Wenn es explizit Base64 sein soll, muss der Aufrufer es als Base64Type setzen oder wir müssen hier dekodieren.
                                            } else {
                                                 LOGGER.warn("Anhang {} Attachment-Daten sind nicht vom Typ Base64BinaryType oder haben keinen Wert. Überspringe eventuelle Dekodierung.", anhangIdLog);
                                            }

                                            if (attachmentData.length > MAX_ATTACHMENT_SIZE_BYTES) {
                                                LOGGER.error("Anhang {} Attachment-Daten überschreiten die maximale Größe von {} Bytes.", anhangIdLog, MAX_ATTACHMENT_SIZE_BYTES);
                                                throw new UnprocessableEntityException("Anhang " + anhangIdLog + " Attachment überschreitet die maximale Größe von 10MB.");
                                            }

                                            LOGGER.debug("Speichere Attachment-Daten ({} Bytes, Typ: {}) aus Anhang {} als Binary.", attachmentData.length, attachmentContentType, anhangIdLog);
                                            Binary binary = new Binary();
                                            binary.setContentType(attachmentContentType);
                                            binary.setData(attachmentData);
                                            DaoMethodOutcome binaryOutcome = daoRegistry.getResourceDao(Binary.class).create(binary);

                                            if (binaryOutcome.getCreated() != null && binaryOutcome.getCreated()) {
                                                String binaryUrl = binaryOutcome.getId().toUnqualifiedVersionless().getValue();
                                                attachment.setData(null);
                                                attachment.setUrl(binaryUrl);
                                                LOGGER.info("Attachment-Daten aus Anhang {} als Binary ({}) gespeichert und URL in Anhang gesetzt.", anhangIdLog, binaryUrl);
                                            } else {
                                                LOGGER.error("Fehler beim Speichern der Attachment-Daten aus Anhang {} als Binary.", anhangIdLog);
                                                throw new InternalErrorException("Konnte Attachment-Daten aus Anhang " + anhangIdLog + " nicht als Binary speichern.");
                                            }
                                        }
                                    }
                                }
            
                                // Modifizierte Anhang-DocumentReference speichern
                                LOGGER.debug("Speichere verarbeitete Anhang-DocumentReference: {}", anhangIdLog);
                                DaoMethodOutcome savedAnhangOutcome = daoRegistry.getResourceDao(DocumentReference.class).create(anhangToProcess);
                                if (savedAnhangOutcome.getCreated() != null && savedAnhangOutcome.getCreated()) {
                                    savedAnhangDocRef = (DocumentReference) savedAnhangOutcome.getResource();
                                    LOGGER.info("Anhang-DocumentReference {} erfolgreich gespeichert mit ID: {}", anhangIdLog, savedAnhangDocRef.getIdElement().getValue());
                                } else {
                                    LOGGER.error("Fehler beim Speichern der Anhang-DocumentReference {}. Outcome: {}", anhangIdLog, savedAnhangOutcome);
                                    throw new InternalErrorException("Konnte Anhang-DocumentReference " + anhangIdLog + " nicht speichern.");
                                }
            
                            } catch (UnprocessableEntityException | InternalErrorException e) {
                                LOGGER.error("Verarbeitungsfehler bei Anhang {}: {}", anhangIdLog, e.getMessage());
                                // Füge den Fehler zum Haupt-OperationOutcome hinzu oder werfe direkt?
                                // Fürs Erste: Fehlerhafte Anhänge werden nicht in die Results aufgenommen und nicht referenziert.
                                // Man könnte hier auch eine spezielle Fehlerstruktur im ProcessedAttachmentResult einführen.
                                SingleValidationMessage errorMsg = new SingleValidationMessage();
                                errorMsg.setSeverity(ResultSeverityEnum.ERROR);
                                errorMsg.setMessage("Fehler bei Verarbeitung von Anhang " + anhangIdLog + ": " + e.getMessage());
                                errorMsg.setLocationString(anhangDocRef.fhirType() + (anhangDocRef.hasId() ? "/"+anhangDocRef.getIdPart() : ""));
                                allWarningsAndInfos.add(errorMsg); // Zum globalen Report hinzufügen
                                continue; // Nächsten Anhang bearbeiten
                            } catch (Exception e) {
                                LOGGER.error("Unerwarteter Fehler bei Verarbeitung von Anhang {}.", anhangIdLog, e);
                                SingleValidationMessage errorMsg = new SingleValidationMessage();
                                errorMsg.setSeverity(ResultSeverityEnum.FATAL);
                                errorMsg.setMessage("Unerwarteter Fehler bei Verarbeitung von Anhang " + anhangIdLog + ": " + e.getMessage());
                                errorMsg.setLocationString(anhangDocRef.fhirType() + (anhangDocRef.hasId() ? "/"+anhangDocRef.getIdPart() : ""));
                                allWarningsAndInfos.add(errorMsg); // Zum globalen Report hinzufügen
                                continue; // Nächsten Anhang bearbeiten
                            }
            
                            OperationOutcome anhangWarningsOutcome = null;
                            if (!attachmentSpecificWarnings.isEmpty()) {
                                anhangWarningsOutcome = createOperationOutcomeFromMessages(attachmentSpecificWarnings);
                            }
                            if (savedAnhangDocRef != null) { // Nur erfolgreich gespeicherte Anhänge hinzufügen
                                processedAttachmentResults.add(new ProcessedAttachmentResult(savedAnhangDocRef, anhangWarningsOutcome));
                            }
                        }
                        LOGGER.info("Verarbeitung von {} Anhängen beendet.", anhaenge.size());
                    }
            
                    // Referenzen zu verarbeiteten Anhängen in transformierter Hauptrechnung hinzufügen
                    if (!processedAttachmentResults.isEmpty()) {
                        DocumentReference.DocumentReferenceContextComponent context = transformedRechnung.getContext();
                        if (!transformedRechnung.hasContext()) { 
                            context = new DocumentReference.DocumentReferenceContextComponent();
                            transformedRechnung.setContext(context);
                        }
                        for (ProcessedAttachmentResult processedAttachment : processedAttachmentResults) {
                            if (processedAttachment.savedAttachmentDocumentReference != null && processedAttachment.savedAttachmentDocumentReference.hasId()) {
                                Reference anhangRef = new Reference(processedAttachment.savedAttachmentDocumentReference.getIdElement().toUnqualifiedVersionless());
                                anhangRef.setType("DocumentReference");
                                context.addRelated(anhangRef);
                                LOGGER.info("Referenz zu Anhang {} zur transformierten Hauptrechnung hinzugefügt.", processedAttachment.savedAttachmentDocumentReference.getIdElement().getValue());
                            }
                        }
                    }
                    // ---------- ENDE NEUE POSITION für Anhangverarbeitung ----------

                    // Signatur für die transformierte Rechnung (wenn anwendbar)
                    boolean isRechnungType = rechnung.getType() != null &&
                            rechnung.getType().getCoding().stream().anyMatch(coding ->
                                    "http://dvmd.de/fhir/CodeSystem/kdl".equals(coding.getSystem()) &&
                                            "AM010106".equals(coding.getCode())
                            );

                    if (isRechnungType) {
                        if (pdfDataForSigning != null && invoiceJsonDataForSigning != null) {
                            try {
                                LOGGER.info("Versuche, die transformierte Rechnung ID {} zu signieren.", generatedTokenId);
                                signTransformedDocument(transformedRechnung, pdfDataForSigning, invoiceJsonDataForSigning);
                                LOGGER.info("Transformierte Rechnung ID {} erfolgreich signiert.", generatedTokenId);
                            } catch (Exception eSign) {
                                LOGGER.error("Fehler bei der Signaturerstellung für transformierte Rechnung ID {}.", generatedTokenId, eSign);
                                throw new UnprocessableEntityException("Fehler bei der Signaturerstellung für transformierte Rechnung: " + eSign.getMessage(), eSign);
                            }
                        } else {
                            LOGGER.warn("Überspringe Signatur für transformierte Rechnung ID {}: PDF-Daten oder Invoice-JSON-Daten für die Signatur fehlen.", generatedTokenId);
                        }
                    } else {
                        LOGGER.info("Überspringe Signatur für transformierte Rechnung ID {}: Dokumenttyp ist nicht 'Rechnung' oder Typinformation fehlt.", generatedTokenId);
                    }

                    // Speichere die transformierte DocumentReference via UPDATE
                    LOGGER.info("Speichere transformierte DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId);
                    try {
                        // DEBUG: Logge den Kontext direkt vor dem Speichern
                        if (transformedRechnung.hasContext() && transformedRechnung.getContext().hasRelated()) {
                            LOGGER.info("DEBUG: finalTransformedRechnung.context.related VOR UPDATE (Anzahl: {}):", transformedRechnung.getContext().getRelated().size());
                            for (Reference r : transformedRechnung.getContext().getRelated()) {
                                LOGGER.info("DEBUG: related item: {} - {}", r.getType(), r.getReference());
                            }
                        } else {
                            LOGGER.info("DEBUG: finalTransformedRechnung hat keinen Context oder keine related Einträge VOR UPDATE.");
                        }

                        DaoMethodOutcome transformedDocRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).update(transformedRechnung);
                        if (transformedDocRefOutcome.getId() != null && generatedTokenId.equals(transformedDocRefOutcome.getId().getIdPart())) {
                            LOGGER.info("Transformierte DocumentReference erfolgreich mit ID {} aktualisiert/gespeichert.", transformedDocRefOutcome.getId().getValue());
                            finalTransformedRechnung = transformedRechnung; // Für Rückgabe speichern
                        } else {
                            LOGGER.warn("Speichern/Update der transformierten DocumentReference war nicht erfolgreich oder gab unerwartetes Ergebnis zurück. Outcome: {}", transformedDocRefOutcome);
                            throw new InternalErrorException("Fehler beim Update der transformierten Rechnung: Unerwartetes Ergebnis vom DAO.");
                        }
                    } catch (Exception eTrans) {
                        LOGGER.error("Fehler beim Speichern/Update der transformierten DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId, eTrans);
                        throw new InternalErrorException("Fehler beim Speichern/Update der transformierten Rechnung: " + eTrans.getMessage(), eTrans);
                    }

                } else {
                    LOGGER.warn("Speichern der initialen DocumentReference hat kein 'created=true' zurückgegeben. Outcome: {}", docRefOutcome);
                    // KEINE Anhangverarbeitung, da die initiale Rechnung nicht gespeichert wurde
                }
            } catch (Exception e) {
                LOGGER.error("Fehler beim Speichern der initialen DocumentReference (Rechnung) in der Datenbank.", e);
                throw new InternalErrorException("Fehler beim Speichern der Rechnung: " + e.getMessage(), e);
            }
        } else {
            LOGGER.info("Modus 'test': Nur Validierung durchgeführt, kein Speichern oder Transformieren.");
            // Im Test-Modus KEINE Anhangverarbeitung durchführen, die Speichern erfordert
        }

        // 4. Finale Warnungen/Infos sammeln und Ergebnis zurückgeben
        OperationOutcome finalWarningsOutcome = null;
        if (!allWarningsAndInfos.isEmpty()) {
            finalWarningsOutcome = createOperationOutcomeFromMessages(allWarningsAndInfos);
            LOGGER.info("Validierungswarnungen/-informationen für Hauptrechnung gefunden und werden zurückgegeben.");
        } else {
            LOGGER.debug("Keine Validierungswarnungen oder -informationen für Hauptrechnung gefunden.");
        }

        // Rückgabe des Ergebnisses (finalTransformedRechnung hat jetzt ggf. die Anhangs-Refs)
        // Beachte: processedAttachmentResults wird hier übergeben, auch wenn es leer sein könnte,
        // aber die Referenzen darauf wurden bereits VOR dem Speichern in finalTransformedRechnung eingefügt.
        return new ValidationAndTransformResult(finalWarningsOutcome, finalTransformedRechnung, processedAttachmentResults);
    }

    /**
     * Verarbeitet das ValidationResult, wirft eine Exception bei Fehlern 
     * und fügt Warnungen/Infos zur übergebenen Liste hinzu.
     */
    private void handleValidationResult(ValidationResult validationResult, List<SingleValidationMessage> warningList, String resourceType) {
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
            .collect(Collectors.toList());
            
        if (!errors.isEmpty()) {
            OperationOutcome errorOutcome = createOperationOutcomeFromMessages(errors);
            String errorMessage = errors.stream()
                .map(single -> resourceType + " -> " + single.getLocationString() + ": " + single.getMessage() + " [" + single.getSeverity() + "]")
                .collect(Collectors.joining("\n"));
            LOGGER.error("Validierungsfehler gefunden für {}: \n{}", resourceType, errorMessage);
            throw new UnprocessableEntityException("Validierungsfehler für " + resourceType + ": " + errorMessage, errorOutcome);
        }

        // Sammle nur Warnungen und Informationen
        validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING || m.getSeverity() == ResultSeverityEnum.INFORMATION)
            .forEach(warningList::add);
    }

    /**
     * Erstellt ein OperationOutcome aus einer Liste von SingleValidationMessage.
     */
    private OperationOutcome createOperationOutcomeFromMessages(List<SingleValidationMessage> messages) {
        OperationOutcome outcome = new OperationOutcome();
        messages.forEach(message -> {
            OperationOutcome.IssueSeverity severity = OperationOutcome.IssueSeverity.NULL;
            switch (message.getSeverity()) {
                case FATAL: severity = OperationOutcome.IssueSeverity.FATAL; break;
                case ERROR: severity = OperationOutcome.IssueSeverity.ERROR; break;
                case WARNING: severity = OperationOutcome.IssueSeverity.WARNING; break;
                case INFORMATION: severity = OperationOutcome.IssueSeverity.INFORMATION; break;
                default: severity = OperationOutcome.IssueSeverity.NULL; break;
            }
            // Hier könnte man versuchen, den IssueType besser zu mappen, aber INVALID ist oft passend
            OperationOutcome.IssueType issueType = OperationOutcome.IssueType.INVALID;
            
            outcome.addIssue()
                .setSeverity(severity)
                .setCode(issueType)
                .setDiagnostics(message.getMessage()) // Nur die Nachricht
                .addLocation(message.getLocationString()); // Ort separat
        });
        return outcome;
    }

    private void signTransformedDocument(DocumentReference documentToSign, byte[] pdfData, byte[] invoiceJsonData) throws Exception {
        LOGGER.debug("Lade Schlüsselmaterial für die Signatur der transformierten Rechnung (ID: {}).", documentToSign.getId());
        // KeyMaterial laden (Pfade und Passwort sind hier hartcodiert, wie im DocumentProcessorService)
        KeyLoader.KeyMaterial keyMaterial = KeyLoader.loadKeyMaterial(
                "src/main/resources/certificates/fachdienst.p12",
                "changeit"
        );
        LOGGER.debug("Schlüsselmaterial geladen. Erstelle CAdES-Signatur.");

        // Signatur erstellen
        byte[] cadesSignature = this.signatureService.signPdfAndFhir(
                pdfData,
                invoiceJsonData,
                keyMaterial.getCertificate(),
                keyMaterial.getPrivateKey()
        );
        LOGGER.debug("CAdES-Signatur erstellt (Größe: {} Bytes). Hänge Signatur an transformierte Rechnung an.", cadesSignature.length);

        // Signatur an DocumentReference anhängen
        this.fhirSignatureService.attachCadesSignature(documentToSign, cadesSignature);
        LOGGER.info("CAdES-Signatur erfolgreich an transformierte DocumentReference (ID: {}) angehängt.", documentToSign.getId());
    }
} 