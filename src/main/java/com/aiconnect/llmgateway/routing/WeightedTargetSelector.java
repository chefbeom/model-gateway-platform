package com.aiconnect.llmgateway.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WeightedTargetSelector {
    private final ActiveRequestRegistry activeRequests;
    private final WeightedSelectionStore selections;
    public WeightedTargetSelector(ActiveRequestRegistry activeRequests) {
        this(activeRequests, new LocalWeightedSelectionStore());
    }
    @Autowired
    public WeightedTargetSelector(ActiveRequestRegistry activeRequests, WeightedSelectionStore selections) {
        this.activeRequests = activeRequests;
        this.selections = selections;
    }
    public List<ResolvedTarget> order(List<ResolvedTarget> candidates) {
        Map<Integer, List<ResolvedTarget>> byPriority = new TreeMap<>();
        for (ResolvedTarget candidate : candidates) byPriority.computeIfAbsent(candidate.target().getPriority(), ignored -> new ArrayList<>()).add(candidate);
        List<ResolvedTarget> ordered = new ArrayList<>();
        for (List<ResolvedTarget> group : byPriority.values()) ordered.addAll(orderPriority(group));
        return ordered;
    }
    private List<ResolvedTarget> orderPriority(List<ResolvedTarget> group) {
        double minimumLoad = group.stream().mapToDouble(this::load).min().orElse(0);
        List<ResolvedTarget> leastLoaded = group.stream().filter(candidate -> Math.abs(load(candidate) - minimumLoad) < 0.000001d).toList();
        ResolvedTarget preferred = leastLoaded.stream().min(Comparator.comparingDouble(this::weightedSelectionScore)
                .thenComparing(candidate -> candidate.deployment().getId())).orElseThrow();
        selections.increment(preferred.target().getId());
        List<ResolvedTarget> ordered = new ArrayList<>(); ordered.add(preferred);
        group.stream().filter(candidate -> candidate != preferred)
                .sorted(Comparator.comparingDouble(this::load).thenComparing(Comparator.comparingInt((ResolvedTarget candidate) -> candidate.target().getWeight()).reversed())
                        .thenComparing(candidate -> candidate.deployment().getId())).forEach(ordered::add);
        return ordered;
    }
    private double load(ResolvedTarget candidate) { return (double) activeRequests.count(candidate.deployment().getId()) / candidate.maxConcurrency(); }
    private double weightedSelectionScore(ResolvedTarget candidate) {
        long selected = selections.count(candidate.target().getId());
        return (double) selected / Math.max(1, candidate.target().getWeight());
    }
}
