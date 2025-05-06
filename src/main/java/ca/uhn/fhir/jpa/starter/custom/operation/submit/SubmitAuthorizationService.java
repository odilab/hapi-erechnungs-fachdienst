package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.springframework.stereotype.Service;

@Service
public class SubmitAuthorizationService {

    public AccessToken authorizeRequest(RequestDetails theRequestDetails) {
        // Berechtigungsprüfung
        Object tokenObj = theRequestDetails.getUserData().get("ACCESS_TOKEN");
        if (!(tokenObj instanceof AccessToken)) {
            throw new AuthenticationException("Kein gültiger Access Token gefunden");
        }

        AccessToken accessToken = (AccessToken) tokenObj;
        Profession profession = accessToken.getProfession();

        if (profession == null || (profession != Profession.LEISTUNGSERBRINGER && profession != Profession.ARZT_KRANKENHAUS)) {
            throw new AuthenticationException("Keine ausreichende Berechtigung für die Submit-Operation. Nur Leistungserbringer und Ärzte im Krankenhaus dürfen Rechnungen einreichen.");
        }

        // Scope-Prüfung gemäß A_26029
        String scope = accessToken.getScope();
        if (!"invoiceDoc.c".equals(scope) && !"openid e-rezept".equals(scope)) {
            throw new ForbiddenOperationException("Fehlender Scope: invoiceDoc.c");
        }

        return accessToken;
    }
} 