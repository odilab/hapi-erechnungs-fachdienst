package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.signature.FhirSignatureService;
import ca.uhn.fhir.jpa.starter.custom.signature.KeyLoader;
import ca.uhn.fhir.jpa.starter.custom.signature.SignatureService;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RechnungProcessingService { 

    private static final Logger LOGGER = LoggerFactory.getLogger(RechnungProcessingService.class); 
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    // FHIR Content Types
    private static final String CONTENT_TYPE_FHIR_JSON = "application/fhir+json";
    private static final String CONTENT_TYPE_FHIR_XML = "application/fhir+xml";
    private static final String CONTENT_TYPE_PDF = "application/pdf";
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";

    // Gematik URLs and Systems - NUR DIE, DIE NICHT IN DEN METADATA HANDLER GEWANDERT SIND
    private static final String GEMATIK_SID_TELEMATIK_ID_SYSTEM_URL = "https://gematik.de/fhir/sid/telematik-id";
    private static final String DVMDE_CS_KDL_URL = "http://dvmd.de/fhir/CodeSystem/kdl";
    private static final String DVMDE_KDL_RECHNUNG_CODE = "AM010106";

    // Default processing mode
    private static final String MODE_NORMAL = "normal";
    private static final String MODE_TEST = "test";


    private final CustomValidator customValidator;
    private final DaoRegistry daoRegistry;
    private final TokenGenerationService tokenGenerationService;
    private final PdfEnrichmentService pdfEnrichmentService;
    private final FhirContext ctx;
    private final SignatureService signatureService;
    private final FhirSignatureService fhirSignatureService;
    private final TransformedRechnungMetadataHandler metadataHandler; // Injizieren
    private final AttachmentProcessingService attachmentProcessingService; // Injizieren

    /**
     * Container-Klasse für das Ergebnis der Verarbeitung des DocumentReference-Inhalts.
     */
    private static class ProcessedContentResult {
        String patientReference = null;
        byte[] pdfDataForSigning = null;
        byte[] invoiceJsonDataForSigning = null;
        final Map<Integer, String> invoiceUrlMap = new HashMap<>(); 
        final Map<Integer, byte[]> pdfDataToEnrichMap = new HashMap<>(); 

        ProcessedContentResult() {
            // Konstruktor bleibt leer oder initialisiert ggf. Felder
        }
    }


    @Autowired
    public RechnungProcessingService(CustomValidator customValidator, 
                                DaoRegistry daoRegistry, 
                                TokenGenerationService tokenGenerationService, 
                                PdfEnrichmentService pdfEnrichmentService, 
                                FhirContext ctx, 
                                SignatureService signatureService, 
                                FhirSignatureService fhirSignatureService,
                                TransformedRechnungMetadataHandler metadataHandler,
                                AttachmentProcessingService attachmentProcessingService) { // Injizieren
        this.customValidator = customValidator;
        this.daoRegistry = daoRegistry;
        this.tokenGenerationService = tokenGenerationService;
        this.pdfEnrichmentService = pdfEnrichmentService;
        this.ctx = ctx;
        this.signatureService = signatureService;
        this.fhirSignatureService = fhirSignatureService;
        this.metadataHandler = metadataHandler; // Zuweisen
        this.attachmentProcessingService = attachmentProcessingService; // Zuweisen
    }

    public ValidationAndTransformResult validate(DocumentReference rechnung, CodeType modus, AccessToken accessToken, List<DocumentReference> anhaenge) {
        DocumentReference finalTransformedRechnung = null;
        List<SingleValidationMessage> allWarningsAndInfos = new ArrayList<>();
        List<ProcessedAttachmentResult> processedAttachmentResults = new ArrayList<>();

        if (rechnung == null) {
            LOGGER.warn("validate wurde mit einer null DocumentReference aufgerufen.");
            return new ValidationAndTransformResult(null, null, processedAttachmentResults);
        }

        String modusValue = (modus != null) ? modus.getValueAsString() : MODE_NORMAL;

        LOGGER.debug("Starte Validierung/Verarbeitung für DocumentReference (Rechnung) mit ID: {}. Modus: {}",
                rechnung.hasId() ? rechnung.getIdElement().getValue() : "keine ID", modusValue);

        // 1. FHIR-validieren der Haupt-DocumentReference
        validateInitialDocumentReference(rechnung, allWarningsAndInfos);

        // 2. Extrahiere, parse, validiere (und speichere ggf.) Invoices und sammle PDFs
        ProcessedContentResult contentResult = processMainDocumentContents(rechnung, modusValue, allWarningsAndInfos);
        
        String patientReferenceFromInvoice = contentResult.patientReference;
        byte[] pdfDataForSigning = contentResult.pdfDataForSigning;
        byte[] invoiceJsonDataForSigning = contentResult.invoiceJsonDataForSigning;
        Map<Integer, String> invoiceUrlMap = contentResult.invoiceUrlMap;
        Map<Integer, byte[]> pdfDataToEnrichMap = contentResult.pdfDataToEnrichMap;


        // 3. Speichern und Transformieren der DocumentReference nur im 'normal'-Modus 
        if (MODE_NORMAL.equalsIgnoreCase(modusValue)) {
            DaoMethodOutcome initialDocRefOutcome = saveInitialRechnung(rechnung);

            if (initialDocRefOutcome.getCreated() != null && initialDocRefOutcome.getCreated()) {
                String originalDocRefId = initialDocRefOutcome.getId().getValue();
                DocumentReference savedRechnung = (DocumentReference) initialDocRefOutcome.getResource();
                if (savedRechnung == null) {
                    LOGGER.error("Konnte die gespeicherte initiale DocumentReference nicht vom DaoMethodOutcome abrufen.");
                    throw new InternalErrorException("Fehler nach dem Speichern der Rechnung: Gespeicherte Ressource nicht verfügbar.");
                }

                DocumentReference transformedRechnung = prepareTransformedRechnung(savedRechnung, originalDocRefId, patientReferenceFromInvoice, invoiceUrlMap, accessToken);
                String generatedTokenId = transformedRechnung.getIdElement().getIdPart(); 

                enrichPdfsAndSetUrls(transformedRechnung, pdfDataToEnrichMap, generatedTokenId, savedRechnung);
                
                // Anhänge verarbeiten mit dem neuen Service
                AttachmentProcessingService.AttachmentProcessingOverallResult anhangProcessingResult = 
                    attachmentProcessingService.processAndLinkAttachments(anhaenge, modusValue, transformedRechnung);
                
                processedAttachmentResults = anhangProcessingResult.processedAttachments;
                if (anhangProcessingResult.messages != null) {
                    allWarningsAndInfos.addAll(anhangProcessingResult.messages);
                }

                finalTransformedRechnung = signAndSaveTransformedRechnung(transformedRechnung, rechnung, pdfDataForSigning, invoiceJsonDataForSigning, originalDocRefId);

            } else {
                LOGGER.warn("Speichern der initialen DocumentReference hat kein 'created=true' zurückgegeben. Outcome: {}", initialDocRefOutcome);
            }
        } else {
            LOGGER.info("Modus '{}': Nur Validierung durchgeführt, kein Speichern oder Transformieren.", modusValue);
        }

        OperationOutcome finalWarningsOutcome = createFinalWarningsOutcome(allWarningsAndInfos);

        return new ValidationAndTransformResult(finalWarningsOutcome, finalTransformedRechnung, processedAttachmentResults);
    }


    private void validateInitialDocumentReference(DocumentReference rechnung, List<SingleValidationMessage> allWarningsAndInfos) {
        ValidationResult docRefValidationResult = customValidator.validateAndReturnResult(rechnung);
        handleValidationResult(docRefValidationResult, allWarningsAndInfos, "DocumentReference (Hauptdokument)");
    }

    private ProcessedContentResult processMainDocumentContents(DocumentReference rechnung, String modusValue, List<SingleValidationMessage> allWarningsAndInfos) {
        ProcessedContentResult result = new ProcessedContentResult();

        if (rechnung.hasContent()) {
            for (int i = 0; i < rechnung.getContent().size(); i++) {
                DocumentReference.DocumentReferenceContentComponent content = rechnung.getContent().get(i);
                if (content.hasAttachment() && content.getAttachment().hasContentType() && content.getAttachment().hasData()) {
                    Attachment attachment = content.getAttachment();
                    String contentType = attachment.getContentType();
                    boolean isFhirJson = CONTENT_TYPE_FHIR_JSON.equalsIgnoreCase(contentType);
                    boolean isFhirXml = CONTENT_TYPE_FHIR_XML.equalsIgnoreCase(contentType);
                    boolean isPdf = CONTENT_TYPE_PDF.equalsIgnoreCase(contentType);

                    if (isFhirJson || isFhirXml) {
                        LOGGER.debug("Found potential FHIR Invoice in content index {} with contentType: {}", i, contentType);
                        try {
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

                                if (result.invoiceJsonDataForSigning == null) {
                                    result.invoiceJsonDataForSigning = decodedBytes; 
                                    LOGGER.debug("FHIR Invoice-Daten (Index {}) für Signatur zwischengespeichert.", i);
                                }

                                if (result.patientReference == null && parsedInvoice.hasSubject() && parsedInvoice.getSubject().hasReference()) {
                                    result.patientReference = parsedInvoice.getSubject().getReference();
                                    LOGGER.debug("Patientenreferenz '{}' aus Invoice (Index {}) für context.related extrahiert.", result.patientReference, i);
                                }

                                ValidationResult invoiceValidationResult = customValidator.validateAndReturnResult(parsedInvoice);
                                handleValidationResult(invoiceValidationResult, allWarningsAndInfos, "Invoice (Index " + i + ")");

                                if (MODE_NORMAL.equalsIgnoreCase(modusValue)) {
                                    try {
                                        LOGGER.info("Modus 'normal': Speichere Invoice aus Content-Index {} in der Datenbank.", i);
                                        DaoMethodOutcome savedInvoiceOutcome = daoRegistry.getResourceDao(Invoice.class).create(parsedInvoice);
                                        if (savedInvoiceOutcome.getCreated() != null && savedInvoiceOutcome.getCreated()) {
                                            String invoiceUrl = savedInvoiceOutcome.getId().toUnqualifiedVersionless().getValue();
                                            LOGGER.info("Invoice erfolgreich gespeichert mit ID/URL: {}", invoiceUrl);
                                            result.invoiceUrlMap.put(i, invoiceUrl); 
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
                            }
                        } catch (IllegalArgumentException eDecode) {
                            LOGGER.error("Fehler beim Base64-Dekodieren der Daten aus Content-Index {}.", i, eDecode);
                            throw new UnprocessableEntityException("Ungültige Base64-Kodierung in Attachment bei Index " + i + ": " + eDecode.getMessage());
                        } catch (Exception eParse) {
                            LOGGER.error("Fehler beim Parsen der FHIR-Ressource aus Content-Index {}. ContentType: {}.", i, contentType, eParse);
                            throw new UnprocessableEntityException("FHIR-Parsing-Fehler in Attachment bei Index " + i + ": " + eParse.getMessage());
                        }
                    } else if (isPdf) {
                        LOGGER.debug("Found PDF Attachment in content index {}", i);
                        byte[] pdfData = attachment.getData();
                        if (pdfData.length > MAX_ATTACHMENT_SIZE_BYTES) {
                            LOGGER.error("PDF-Attachment-Daten bei Index {} überschreiten die maximale Größe von {} Bytes.", i, MAX_ATTACHMENT_SIZE_BYTES);
                            throw new UnprocessableEntityException("PDF-Attachment bei Index " + i + " überschreitet die maximale Größe von 10MB.");
                        }
                        try (PDDocument ignored = PDDocument.load(pdfData)) {
                            LOGGER.debug("PDF in content index {} scheint valide zu sein.", i);
                            if (result.pdfDataForSigning == null) {
                                result.pdfDataForSigning = pdfData; 
                                LOGGER.debug("PDF-Daten (Index {}) für Signatur zwischengespeichert.", i);
                            }
                            if (MODE_NORMAL.equalsIgnoreCase(modusValue)) {
                                result.pdfDataToEnrichMap.put(i, pdfData); 
                            }
                        } catch (IOException ePdfLoad) {
                            LOGGER.error("Fehler beim Laden/Validieren der PDF aus Content-Index {}. Ist es eine valide PDF?", i, ePdfLoad);
                            throw new UnprocessableEntityException("Anhang bei Index " + i + " ist keine valide PDF: " + ePdfLoad.getMessage());
                        }
                    }
                }
            }
        }
        return result;
    }


    private DaoMethodOutcome saveInitialRechnung(DocumentReference rechnung) {
        try {
            LOGGER.info("Speichere initiale DocumentReference (Rechnung) mit ID: {} in der Datenbank.",
                    rechnung.hasId() ? rechnung.getIdElement().getValue() : "(wird generiert)");
            return daoRegistry.getResourceDao(DocumentReference.class).create(rechnung);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Speichern der initialen DocumentReference (Rechnung) in der Datenbank.", e);
            throw new InternalErrorException("Fehler beim Speichern der Rechnung: " + e.getMessage(), e);
        }
    }

    private DocumentReference prepareTransformedRechnung(DocumentReference savedRechnung, String originalDocRefId, String patientReferenceFromInvoice, Map<Integer, String> invoiceUrlMap, AccessToken accessToken) {
        DocumentReference transformedRechnung = savedRechnung.copy();
        transformedRechnung.setId((String)null); 

        Meta meta = transformedRechnung.getMeta();
        if (meta == null) {
            meta = new Meta();
            transformedRechnung.setMeta(meta);
        }
        metadataHandler.applyMetadata(meta);

        setTransformedRechnungContextRelatedPatient(transformedRechnung, patientReferenceFromInvoice);
        setInvoiceUrlsInTransformedRechnungContent(transformedRechnung, invoiceUrlMap);
        addRelatesToOriginalDocument(transformedRechnung, originalDocRefId);

        String generatedTokenId = tokenGenerationService.generateUniqueToken();
        transformedRechnung.setId(generatedTokenId);
        LOGGER.info("Generierte eindeutige ID für transformierte Rechnung (und PDF QR-Codes): {}", generatedTokenId);

        setTransformedRechnungAuthor(transformedRechnung, accessToken);

        return transformedRechnung;
    }

    private void setTransformedRechnungContextRelatedPatient(DocumentReference transformedRechnung, String patientReferenceFromInvoice) {
        if (patientReferenceFromInvoice != null) {
            DocumentReference.DocumentReferenceContextComponent context = transformedRechnung.getContext();
            if (!transformedRechnung.hasContext()) {
                context = new DocumentReference.DocumentReferenceContextComponent();
                transformedRechnung.setContext(context);
            }
            context.getRelated().clear();

            Reference relatedPatientRef = new Reference(patientReferenceFromInvoice);
            relatedPatientRef.setType("Patient");
            context.addRelated(relatedPatientRef);
            LOGGER.info("DocumentReference.context.related für transformierte Rechnung mit Patientenreferenz '{}' gesetzt.", patientReferenceFromInvoice);
        } else {
            LOGGER.warn("Keine Patientenreferenz aus einer Invoice gefunden. DocumentReference.context.related (Patient) wird nicht gesetzt.");
        }
    }

    private void setInvoiceUrlsInTransformedRechnungContent(DocumentReference transformedRechnung, Map<Integer, String> invoiceUrlMap) {
        if (!invoiceUrlMap.isEmpty()) {
            LOGGER.debug("Modifiziere transformierte Rechnung: Ersetze Daten durch URLs für Indizes: {}", invoiceUrlMap.keySet());
            for (Map.Entry<Integer, String> entry : invoiceUrlMap.entrySet()) {
                int contentIndex = entry.getKey();
                String invoiceUrl = entry.getValue();
                if (contentIndex < transformedRechnung.getContent().size()) {
                    Attachment attachmentToModify = transformedRechnung.getContent().get(contentIndex).getAttachment();
                    attachmentToModify.setData(null);
                    attachmentToModify.setUrl(invoiceUrl);
                    LOGGER.debug("Content-Index {} in transformierter Rechnung: data entfernt, url '{}' gesetzt.", contentIndex, invoiceUrl);
                } else {
                    LOGGER.error("Interner Fehler: Content-Index {} aus invoiceUrlMap ist außerhalb der Grenzen der transformierten Rechnung (size {}). Überspringe.", contentIndex, transformedRechnung.getContent().size());
                }
            }
        }
    }

    private void addRelatesToOriginalDocument(DocumentReference transformedRechnung, String originalDocRefId) {
        DocumentReference.DocumentReferenceRelatesToComponent relatesTo = new DocumentReference.DocumentReferenceRelatesToComponent();
        relatesTo.setCode(DocumentReference.DocumentRelationshipType.TRANSFORMS);
        relatesTo.setTarget(new Reference(originalDocRefId));
        transformedRechnung.addRelatesTo(relatesTo);
        LOGGER.debug("relatesTo Original DocumentReference ({}) zur transformierten Rechnung hinzugefügt.", originalDocRefId);
    }

    private void setTransformedRechnungAuthor(DocumentReference transformedRechnung, AccessToken accessToken) {
        if (accessToken != null && accessToken.getTelematikId().isPresent()) {
            String telematikId = accessToken.getTelematikId().get();
            transformedRechnung.getAuthor().clear();
            Reference authorReference = new Reference();
            Identifier authorIdentifier = new Identifier()
                .setSystem(GEMATIK_SID_TELEMATIK_ID_SYSTEM_URL)
                .setValue(telematikId);
            authorReference.setIdentifier(authorIdentifier);
            transformedRechnung.addAuthor(authorReference);
            LOGGER.info("DocumentReference.author der transformierten Rechnung mit Telematik-ID {} gesetzt.", telematikId);
        } else {
            LOGGER.warn("Keine Telematik-ID im AccessToken gefunden oder AccessToken ist null. DocumentReference.author wird nicht gesetzt/überschrieben.");
        }
    }
    
    private void enrichPdfsAndSetUrls(DocumentReference transformedRechnung, Map<Integer, byte[]> pdfDataToEnrichMap, String generatedTokenId, DocumentReference originalSavedRechnung) {
        Map<Integer, String> storedPdfBinaryUrlMap = new HashMap<>();
        if (!pdfDataToEnrichMap.isEmpty()) {
            LOGGER.debug("Verarbeite {} PDFs für Anreicherung und Speicherung.", pdfDataToEnrichMap.size());
            for (Map.Entry<Integer, byte[]> pdfEntry : pdfDataToEnrichMap.entrySet()) {
                int pdfContentIndex = pdfEntry.getKey();
                byte[] rawPdfData = pdfEntry.getValue();
                try {
                    LOGGER.info("Reichere PDF aus Content-Index {} mit Token {} an und speichere als Binary.", pdfContentIndex, generatedTokenId);
                    Binary enrichedPdfBinary = pdfEnrichmentService.enrichPdfWithBarcodeAndAttachment(rawPdfData, generatedTokenId, originalSavedRechnung);
                    
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
    }

    private DocumentReference signAndSaveTransformedRechnung(DocumentReference transformedRechnung, DocumentReference originalRechnungInput, byte[] pdfDataForSigning, byte[] invoiceJsonDataForSigning, String originalDocRefId) {
        boolean isRechnungType = originalRechnungInput.getType() != null &&
                originalRechnungInput.getType().getCoding().stream().anyMatch(coding ->
                        DVMDE_CS_KDL_URL.equals(coding.getSystem()) &&
                        DVMDE_KDL_RECHNUNG_CODE.equals(coding.getCode())
                );
    
        String generatedTokenId = transformedRechnung.getIdElement().getIdPart();
    
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
    
        LOGGER.info("Speichere transformierte DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId);
        try {
            if (transformedRechnung.hasContext() && transformedRechnung.getContext().hasRelated()) {
                LOGGER.info("DEBUG: transformedRechnung.context.related VOR UPDATE (Anzahl: {}):", transformedRechnung.getContext().getRelated().size());
                for (Reference r : transformedRechnung.getContext().getRelated()) {
                    LOGGER.info("DEBUG: related item: {} - {}", r.getType(), r.getReference());
                }
            } else {
                LOGGER.info("DEBUG: transformedRechnung hat keinen Context oder keine related Einträge VOR UPDATE.");
            }
    
            DaoMethodOutcome transformedDocRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).update(transformedRechnung);
            if (transformedDocRefOutcome.getId() != null && generatedTokenId.equals(transformedDocRefOutcome.getId().getIdPart())) {
                LOGGER.info("Transformierte DocumentReference erfolgreich mit ID {} aktualisiert/gespeichert.", transformedDocRefOutcome.getId().getValue());
                return transformedRechnung;
            } else {
                LOGGER.warn("Speichern/Update der transformierten DocumentReference war nicht erfolgreich oder gab unerwartetes Ergebnis zurück. Outcome: {}", transformedDocRefOutcome);
                throw new InternalErrorException("Fehler beim Update der transformierten Rechnung: Unerwartetes Ergebnis vom DAO.");
            }
        } catch (Exception eTrans) {
            LOGGER.error("Fehler beim Speichern/Update der transformierten DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId, eTrans);
            throw new InternalErrorException("Fehler beim Speichern/Update der transformierten Rechnung: " + eTrans.getMessage(), eTrans);
        }
    }

    private OperationOutcome createFinalWarningsOutcome(List<SingleValidationMessage> allWarningsAndInfos) {
        OperationOutcome finalWarningsOutcome = null;
        if (!allWarningsAndInfos.isEmpty()) {
            finalWarningsOutcome = createOperationOutcomeFromMessages(allWarningsAndInfos);
            LOGGER.info("Validierungswarnungen/-informationen gefunden und werden zurückgegeben.");
        } else {
            LOGGER.debug("Keine Validierungswarnungen oder -informationen gefunden.");
        }
        return finalWarningsOutcome;
    }

    public void handleValidationResult(ValidationResult validationResult, List<SingleValidationMessage> warningList, String resourceType) {
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

        validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING || m.getSeverity() == ResultSeverityEnum.INFORMATION)
            .forEach(warningList::add);
    }

    public OperationOutcome createOperationOutcomeFromMessages(List<SingleValidationMessage> messages) {
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
            OperationOutcome.IssueType issueType = OperationOutcome.IssueType.INVALID;
            
            outcome.addIssue()
                .setSeverity(severity)
                .setCode(issueType)
                .setDiagnostics(message.getMessage()) 
                .addLocation(message.getLocationString()); 
        });
        return outcome;
    }

    private void signTransformedDocument(DocumentReference documentToSign, byte[] pdfData, byte[] invoiceJsonData) throws Exception {
        LOGGER.debug("Lade Schlüsselmaterial für die Signatur der transformierten Rechnung (ID: {}).", documentToSign.getId());
        KeyLoader.KeyMaterial keyMaterial = KeyLoader.loadKeyMaterial(
                "src/main/resources/certificates/fachdienst.p12",
                "changeit"
        );
        LOGGER.debug("Schlüsselmaterial geladen. Erstelle CAdES-Signatur.");

        byte[] cadesSignature = this.signatureService.signPdfAndFhir(
                pdfData,
                invoiceJsonData,
                keyMaterial.getCertificate(),
                keyMaterial.getPrivateKey()
        );
        LOGGER.debug("CAdES-Signatur erstellt (Größe: {} Bytes). Hänge Signatur an transformierte Rechnung an.", cadesSignature.length);

        this.fhirSignatureService.attachCadesSignature(documentToSign, cadesSignature);
        LOGGER.info("CAdES-Signatur erfolgreich an transformierte DocumentReference (ID: {}) angehängt.", documentToSign.getId());
    }
} 