package com.reputul.backend.dto;

import java.util.List;
import java.util.Map;

public class CsvImportPrepareResponse {

    private String uploadId;
    private String detectedDelimiter;
    private List<String> detectedHeaders;
    private Map<String, String> suggestedMapping;
    private List<Map<String, Object>> sampleRows;
    private List<CsvRowValidation> validationResults;
    private List<DedupeCandidate> dedupeResults;
    private int totalRows;
    private int validRows;
    private int invalidRows;

    // Constructors
    public CsvImportPrepareResponse() {}

    public CsvImportPrepareResponse(String uploadId) {
        this.uploadId = uploadId;
    }

    // Inner classes for validation results
    public static class CsvRowValidation {
        private int rowNumber;
        private boolean isValid;
        private List<String> errors;
        private Map<String, Object> rowData;

        public CsvRowValidation(int rowNumber, boolean isValid, List<String> errors, Map<String, Object> rowData) {
            this.rowNumber = rowNumber;
            this.isValid = isValid;
            this.errors = errors;
            this.rowData = rowData;
        }

        // Getters
        public int getRowNumber() { return rowNumber; }
        public boolean isValid() { return isValid; }
        public List<String> getErrors() { return errors; }
        public Map<String, Object> getRowData() { return rowData; }
    }

    public static class DedupeCandidate {
        private int rowNumber;
        private String matchReason;  // "email", "phone", "name_date"
        private Long existingContactId;
        private String existingContactName;
        private String existingContactEmail;
        private String existingContactPhone;
        private Map<String, Object> incomingData;

        public DedupeCandidate(int rowNumber, String matchReason, Long existingContactId, String existingContactName) {
            this.rowNumber = rowNumber;
            this.matchReason = matchReason;
            this.existingContactId = existingContactId;
            this.existingContactName = existingContactName;
        }

        // Getters and Setters
        public int getRowNumber() { return rowNumber; }
        public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }

        public String getMatchReason() { return matchReason; }
        public void setMatchReason(String matchReason) { this.matchReason = matchReason; }

        public Long getExistingContactId() { return existingContactId; }
        public void setExistingContactId(Long existingContactId) { this.existingContactId = existingContactId; }

        public String getExistingContactName() { return existingContactName; }
        public void setExistingContactName(String existingContactName) { this.existingContactName = existingContactName; }

        public String getExistingContactEmail() { return existingContactEmail; }
        public void setExistingContactEmail(String existingContactEmail) { this.existingContactEmail = existingContactEmail; }

        public String getExistingContactPhone() { return existingContactPhone; }
        public void setExistingContactPhone(String existingContactPhone) { this.existingContactPhone = existingContactPhone; }

        public Map<String, Object> getIncomingData() { return incomingData; }
        public void setIncomingData(Map<String, Object> incomingData) { this.incomingData = incomingData; }
    }

    // Getters and Setters
    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getDetectedDelimiter() { return detectedDelimiter; }
    public void setDetectedDelimiter(String detectedDelimiter) { this.detectedDelimiter = detectedDelimiter; }

    public List<String> getDetectedHeaders() { return detectedHeaders; }
    public void setDetectedHeaders(List<String> detectedHeaders) { this.detectedHeaders = detectedHeaders; }

    public Map<String, String> getSuggestedMapping() { return suggestedMapping; }
    public void setSuggestedMapping(Map<String, String> suggestedMapping) { this.suggestedMapping = suggestedMapping; }

    public List<Map<String, Object>> getSampleRows() { return sampleRows; }
    public void setSampleRows(List<Map<String, Object>> sampleRows) { this.sampleRows = sampleRows; }

    public List<CsvRowValidation> getValidationResults() { return validationResults; }
    public void setValidationResults(List<CsvRowValidation> validationResults) { this.validationResults = validationResults; }

    public List<DedupeCandidate> getDedupeResults() { return dedupeResults; }
    public void setDedupeResults(List<DedupeCandidate> dedupeResults) { this.dedupeResults = dedupeResults; }

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    public int getValidRows() { return validRows; }
    public void setValidRows(int validRows) { this.validRows = validRows; }

    public int getInvalidRows() { return invalidRows; }
    public void setInvalidRows(int invalidRows) { this.invalidRows = invalidRows; }
}