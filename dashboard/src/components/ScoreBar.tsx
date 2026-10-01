/** Flakiness score 0..1 as a small meter. The number is always shown; colour is only a secondary cue. */
export function ScoreBar({ score }: { score: number }) {
  const clamped = Math.min(1, Math.max(0, score))
  const level = clamped >= 0.6 ? 'high' : clamped >= 0.3 ? 'medium' : 'low'
  return (
    <div className="score" title={`Flakiness score ${clamped.toFixed(2)}`}>
      <div
        className="score-track"
        role="meter"
        aria-label="Flakiness score"
        aria-valuemin={0}
        aria-valuemax={1}
        aria-valuenow={clamped}
      >
        <div className={`score-fill score-${level}`} style={{ width: `${clamped * 100}%` }} />
      </div>
      <span className="score-value">{clamped.toFixed(2)}</span>
    </div>
  )
}
