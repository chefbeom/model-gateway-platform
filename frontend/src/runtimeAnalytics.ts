export type NumericSummary = { sampleCount: number; average: number | null; minimum: number | null; maximum: number | null }
export type RequestMetrics = {
  requestCount: number; completedRequests: number; succeeded: number; failed: number; inProgress: number
  successRatePercent: number; failoverRequests: number; failoverAttempts: number
  inputTokens: number; outputTokens: number; reasoningTokens: number; cachedInputTokens: number
  unknownCostRequests: number; latencyMs: NumericSummary; inputTokensStats: NumericSummary
  outputTokensStats: NumericSummary; totalTokensStats: NumericSummary; estimatedCostByCurrency: Record<string, number>
}
export type AttemptMetrics = { total: number; succeeded: number; failed: number; inProgress: number; successRatePercent: number; latencyMs: NumericSummary }
export type RuntimeUsage = { serverId: string; serverType: string; serverName: string; detail: string; requests: RequestMetrics; attempts: AttemptMetrics }
export type ModelUsage = { deploymentId: string; modelName: string; detail: string; requests: RequestMetrics; attempts: AttemptMetrics }
export type Breakdown = { label: string; requests: number; percentOfRequests: number }
export type TimeSeriesPoint = { period: string; requests: number; succeeded: number; failed: number; tokens: number }
export type TraceUsage = { label: string; detail: string; requestCount: number; succeeded: number; failed: number; successRatePercent: number; streamingRequests: number; unknownTokenRequests: number; latencyMs: NumericSummary; inputTokens: NumericSummary; outputTokens: NumericSummary; totalTokens: NumericSummary }
export type PlaygroundAnalytics = { total: TraceUsage; byServer: TraceUsage[]; byModel: TraceUsage[]; byRequestType: Breakdown[]; byStreamMode: Breakdown[]; byErrorCode: Breakdown[]; timeSeries: TimeSeriesPoint[] }
export type RuntimeAnalytics = {
  byServer: RuntimeUsage[]; byModel: ModelUsage[]; byRequestType: Breakdown[]; byCapability: Breakdown[]
  byStreamMode: Breakdown[]; byReasoningEffort: Breakdown[]; byServiceTier: Breakdown[]
  byFailureCode: Breakdown[]; byAttemptFailure: Array<{ serverName: string; modelName: string; errorType: string; count: number }>
  timeSeries: TimeSeriesPoint[]; timeSeriesGranularity: 'DAY' | 'MONTH'; playground: PlaygroundAnalytics | null
}
