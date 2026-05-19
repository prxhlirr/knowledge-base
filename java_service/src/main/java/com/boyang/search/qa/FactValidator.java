package com.boyang.search.qa;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FactValidator {
    public ValidationResult validate(QuestionPlan questionPlan, FactExtractionResult facts) {
        ValidationResult result = new ValidationResult();
        List<FactUnit> factList = facts == null ? null : facts.getFacts();
        if (factList == null || factList.isEmpty()) {
            result.setPass(false);
            result.getErrors().add("no_structured_facts");
            return result;
        }

        Set<Integer> citations = new HashSet<>();
        int unknownStatus = 0;
        for (FactUnit fact : factList) {
            if (fact.getCitationIndex() > 0) {
                citations.add(fact.getCitationIndex());
            }
            if ("UNKNOWN".equals(fact.getStatus())) {
                unknownStatus++;
            }
        }
        result.getDetails().put("fact_count", factList.size());
        result.getDetails().put("citation_count", citations.size());
        result.getDetails().put("unknown_status_count", unknownStatus);

        if (citations.isEmpty()) {
            result.setPass(false);
            result.getErrors().add("facts_have_no_citations");
            return result;
        }

        boolean statusSensitive = questionPlan != null && questionPlan.isStatusSensitive();
        if (statusSensitive && unknownStatus == factList.size()) {
            result.setPass(false);
            result.getErrors().add("status_question_without_structured_status_fact");
            return result;
        }

        result.setPass(true);
        return result;
    }
}
