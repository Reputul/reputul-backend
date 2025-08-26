package com.reputul.backend.services.imports;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class CsvParser {

    private static final Logger logger = LoggerFactory.getLogger(CsvParser.class);

    // Column mapping synonyms
    private static final Map<String, Set<String>> COLUMN_SYNONYMS = Map.of(
            "name", Set.of("name", "full_name", "customer_name", "fullname"),
            "email", Set.of("email", "e-mail", "mail", "email_address"),
            "phone", Set.of("phone", "mobile", "cell", "telephone", "phone_number"),
            "lastJobDate", Set.of("last_job_date", "job_date", "service_date", "lastjobdate"),
            "tags", Set.of("tags", "label", "labels", "groups", "categories")
    );

    // Date formats to try
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE, // yyyy-MM-dd
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy")
    );

    public static class ParsedCsvData {
        private final String detectedDelimiter;
        private final List<String> headers;
        private final List<Map<String, String>> rows;

        public ParsedCsvData(String detectedDelimiter, List<String> headers, List<Map<String, String>> rows) {
            this.detectedDelimiter = detectedDelimiter;
            this.headers = headers;
            this.rows = rows;
        }

        // Getters
        public String getDetectedDelimiter() { return detectedDelimiter; }
        public List<String> getHeaders() { return headers; }
        public List<Map<String, String>> getRows() { return rows; }
    }

    /**
     * Parse CSV from InputStream
     */
    public ParsedCsvData parseCsv(InputStream inputStream) throws IOException {
        char delimiter = detectDelimiter(inputStream);

        // Reset stream and parse with detected delimiter
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(inputStream))
                .withCSVParser(new CSVParserBuilder().withSeparator(delimiter).build())
                .build()) {

            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) {
                throw new IOException("CSV file is empty");
            }

            // Extract headers
            List<String> headers = Arrays.stream(allRows.get(0))
                    .map(String::trim)
                    .collect(Collectors.toList());

            // Parse data rows
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < allRows.size() && i <= 10000; i++) { // Limit to 10k rows
                String[] row = allRows.get(i);
                Map<String, String> rowMap = new HashMap<>();

                for (int j = 0; j < Math.min(headers.size(), row.length); j++) {
                    String value = row[j] != null ? row[j].trim() : "";
                    rowMap.put(headers.get(j), value.isEmpty() ? null : value);
                }

                rows.add(rowMap);
            }

            return new ParsedCsvData(String.valueOf(delimiter), headers, rows);

        } catch (CsvException e) {
            throw new IOException("Failed to parse CSV: " + e.getMessage(), e);
        }
    }

    /**
     * Detect CSV delimiter by analyzing the first few lines
     */
    private char detectDelimiter(InputStream inputStream) throws IOException {
        char[] delimiters = {',', ';', '\t', '|'};
        Map<Character, Integer> scores = new HashMap<>();

        try (Scanner scanner = new Scanner(inputStream)) {
            int linesToCheck = Math.min(5, 100); // Check first 5 lines
            int linesChecked = 0;

            while (scanner.hasNextLine() && linesChecked < linesToCheck) {
                String line = scanner.nextLine();
                linesChecked++;

                for (char delimiter : delimiters) {
                    int count = line.length() - line.replace(String.valueOf(delimiter), "").length();
                    scores.merge(delimiter, count, Integer::sum);
                }
            }
        }

        // Return delimiter with highest score, default to comma
        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(',');
    }

    /**
     * Suggest column mapping based on headers
     */
    public Map<String, String> suggestMapping(List<String> headers) {
        Map<String, String> mapping = new HashMap<>();

        for (String header : headers) {
            String normalizedHeader = header.toLowerCase().trim().replaceAll("\\s+", "_");

            for (Map.Entry<String, Set<String>> entry : COLUMN_SYNONYMS.entrySet()) {
                if (entry.getValue().contains(normalizedHeader)) {
                    mapping.put(entry.getKey(), header);
                    break;
                }
            }
        }

        return mapping;
    }

    /**
     * Parse date from string trying multiple formats
     */
    public LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        dateStr = dateStr.trim();

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (DateTimeParseException ignored) {
                // Try next formatter
            }
        }

        logger.debug("Failed to parse date: {}", dateStr);
        return null;
    }

    /**
     * Parse tags from string (comma or semicolon separated)
     */
    public Set<String> parseTags(String tagsStr) {
        if (tagsStr == null || tagsStr.trim().isEmpty()) {
            return new HashSet<>();
        }

        return Arrays.stream(tagsStr.split("[,;]"))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}