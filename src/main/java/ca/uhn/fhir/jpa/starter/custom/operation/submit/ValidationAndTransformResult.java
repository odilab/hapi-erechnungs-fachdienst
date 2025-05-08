package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import java.util.List;

/**
 * Container-Klasse für das Ergebnis der Validierung und Transformation.
 */
public class ValidationAndTransformResult {
    public final OperationOutcome warnings;
    public final DocumentReference transformedRechnung;
    public final List<ProcessedAttachmentResult> processedAttachments;

    public ValidationAndTransformResult(OperationOutcome warnings, DocumentReference transformedRechnung, List<ProcessedAttachmentResult> processedAttachments) {
        this.warnings = warnings;
        this.transformedRechnung = transformedRechnung;
        this.processedAttachments = processedAttachments;
    }
} 