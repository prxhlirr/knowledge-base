package com.boyang.search.qa;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class ClaimPlanValidator {
    public ValidationResult validate(ClaimPlan claimPlan, FactExtractionResult facts) {
        ValidationResult result = new ValidationResult();
        if (claimPlan == null || claimPlan.getClaims().isEmpty()) {
            result.setPass(false);
            result.getErrors().add("claim_plan_empty");
            return result;
        }

        Set<String> factIds = new HashSet<>();
        Set<Integer> citationIndexes = new HashSet<>();
        if (facts != null) {
            for (FactUnit fact : facts.getFacts()) {
                factIds.add(fact.getFactId());
                if (fact.getCitationIndex() > 0) {
                    citationIndexes.add(fact.getCitationIndex());
                }
            }
        }

        int unsupportedClaims = 0;
        for (ClaimUnit claim : claimPlan.getClaims()) {
            if (claim.getSupportingFactIds().isEmpty()) {
                unsupportedClaims++;
                result.getErrors().add("claim_missing_fact: " + claim.getClaimId());
            }
            for (String factId : claim.getSupportingFactIds()) {
                if (!factIds.contains(factId)) {
                    unsupportedClaims++;
                    result.getErrors().add("claim_unknown_fact: " + claim.getClaimId() + "/" + factId);
                }
            }
            if (claim.getCitationIndexes().isEmpty()) {
                unsupportedClaims++;
                result.getErrors().add("claim_missing_citation: " + claim.getClaimId());
            }
            for (Integer citation : claim.getCitationIndexes()) {
                if (!citationIndexes.contains(citation)) {
                    unsupportedClaims++;
                    result.getErrors().add("claim_unknown_citation: " + claim.getClaimId() + "/" + citation);
                }
            }
        }
        result.getDetails().put("claim_count", claimPlan.getClaims().size());
        result.getDetails().put("unsupported_claim_count", unsupportedClaims);
        result.setPass(unsupportedClaims == 0);
        return result;
    }
}
