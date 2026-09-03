package com.aiconnect.llmgateway.dataprotection;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record DataProtectionScanResult(Set<DataClassification> classifications,
                                       Map<DataClassification, Integer> counts) {
    public DataProtectionScanResult {
        classifications = classifications == null || classifications.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(classifications));
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    public boolean hasSensitiveData() { return !classifications.isEmpty(); }
    public boolean has(DataClassification classification) { return classifications.contains(classification); }
    public List<String> labels() { return classifications.stream().map(Enum::name).sorted().toList(); }
}
