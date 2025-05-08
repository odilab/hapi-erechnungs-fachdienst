package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Container-Klasse für das Ergebnis der Anhangverarbeitung.
 */
public class ProcessedAttachmentResult {
    public final DocumentReference savedAttachmentDocumentReference;
    public final OperationOutcome attachmentProcessingWarnings;

    public ProcessedAttachmentResult(DocumentReference savedAttachmentDocumentReference, OperationOutcome attachmentProcessingWarnings) {
        this.savedAttachmentDocumentReference = savedAttachmentDocumentReference;
        this.attachmentProcessingWarnings = attachmentProcessingWarnings;
    }
} 